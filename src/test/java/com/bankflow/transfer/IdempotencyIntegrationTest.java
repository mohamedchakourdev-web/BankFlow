package com.bankflow.transfer;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.outbox.OutboxEventRepository;
import com.bankflow.transfer.repository.TransferRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    private static final String PASSWORD = "SecurePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    delete from idempotency_keys
                    where user_id in (select id from users where email like 'idemp-%')
                    """);
            jdbcTemplate.update("""
                    delete from outbox_events
                    where aggregate_id in (
                        select id from transfers
                        where source_account_id in (
                            select id from accounts where user_id in (select id from users where email like 'idemp-%')
                        )
                        or destination_account_id in (
                            select id from accounts where user_id in (select id from users where email like 'idemp-%')
                        )
                    )
                    """);
            jdbcTemplate.update("""
                    delete from transactions
                    where account_id in (
                        select id from accounts where user_id in (select id from users where email like 'idemp-%')
                    )
                    """);
            jdbcTemplate.update("""
                    delete from transfers
                    where source_account_id in (
                        select id from accounts where user_id in (select id from users where email like 'idemp-%')
                    )
                    or destination_account_id in (
                        select id from accounts where user_id in (select id from users where email like 'idemp-%')
                    )
                    """);
            jdbcTemplate.update("""
                    delete from accounts
                    where user_id in (select id from users where email like 'idemp-%')
                    """);
            jdbcTemplate.update("""
                    delete from notifications
                    where user_id in (select id from users where email like 'idemp-%')
                    """);
            jdbcTemplate.update("""
                    delete from audit_logs
                    where user_id in (select id from users where email like 'idemp-%')
                    """);
            jdbcTemplate.update("delete from users where email like 'idemp-%'");
        });
    }

    @Test
    void repeatedKeyReturnsTheOriginalTransferWithoutMovingMoneyAgain() throws Exception {
        String token = registerAndLogin("Alice", "Retry");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "100.0000");
        String body = transferJson(source, destination, "40.00", "EUR");

        MvcResult created = transfer(token, "retry-key", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        String transferId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        transfer(token, "retry-key", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.password").doesNotExist());

        assertEquals(0, balance(source).compareTo(new BigDecimal("60.0000")));
        assertEquals(0, balance(destination).compareTo(new BigDecimal("40.0000")));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
        assertEquals(1, outboxEventRepository.countByAggregateId(UUID.fromString(transferId)));
    }

    @Test
    void sameKeyWithADifferentAmountIsRejected() throws Exception {
        String token = registerAndLogin("Alice", "Amount");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "100.0000");

        transfer(token, "amount-key", transferJson(source, destination, "40.00", "EUR"))
                .andExpect(status().isCreated());
        transfer(token, "amount-key", transferJson(source, destination, "50.00", "EUR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency key was already used for a different request"));

        assertEquals(0, balance(source).compareTo(new BigDecimal("60.0000")));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void sameKeyWithADifferentDestinationIsRejected() throws Exception {
        String token = registerAndLogin("Alice", "Destination");
        JsonNode source = createAccount(token, "EUR");
        JsonNode first = createAccount(token, "EUR");
        JsonNode second = createAccount(token, "EUR");
        fund(source, "100.0000");

        transfer(token, "dest-key", transferJson(source, first, "15.00", "EUR"))
                .andExpect(status().isCreated());
        transfer(token, "dest-key", transferJson(source, second, "15.00", "EUR"))
                .andExpect(status().isConflict());

        assertEquals(0, balance(source).compareTo(new BigDecimal("85.0000")));
        assertEquals(0, balance(first).compareTo(new BigDecimal("15.0000")));
        assertEquals(0, balance(second).compareTo(BigDecimal.ZERO));
    }

    @Test
    void sameKeyWithADifferentCurrencyIsRejected() throws Exception {
        String token = registerAndLogin("Alice", "Currency");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "80.0000");

        transfer(token, "currency-key", transferJson(source, destination, "10.00", "EUR"))
                .andExpect(status().isCreated());
        transfer(token, "currency-key", transferJson(source, destination, "10.00", "USD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency key was already used for a different request"));

        assertEquals(0, balance(source).compareTo(new BigDecimal("70.0000")));
    }

    @Test
    void missingEmptyAndLongKeysAreRejected() throws Exception {
        String token = registerAndLogin("Alice", "Header");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "30.0000");
        String body = transferJson(source, destination, "5.00", "EUR");

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key is required"));

        transfer(token, "   ", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key must not be empty"));

        transfer(token, "k".repeat(129), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key must be at most 128 characters"));

        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void differentUsersMayUseTheSameKey() throws Exception {
        String alice = registerAndLogin("Alice", "Shared");
        String bob = registerAndLogin("Bob", "Shared");
        JsonNode aliceSource = createAccount(alice, "EUR");
        JsonNode aliceDestination = createAccount(alice, "EUR");
        JsonNode bobSource = createAccount(bob, "EUR");
        JsonNode bobDestination = createAccount(bob, "EUR");
        fund(aliceSource, "40.0000");
        fund(bobSource, "40.0000");

        transfer(alice, "shared-key", transferJson(aliceSource, aliceDestination, "10.00", "EUR"))
                .andExpect(status().isCreated());
        transfer(bob, "shared-key", transferJson(bobSource, bobDestination, "12.00", "EUR"))
                .andExpect(status().isCreated());

        assertEquals(0, balance(aliceSource).compareTo(new BigDecimal("30.0000")));
        assertEquals(0, balance(bobSource).compareTo(new BigDecimal("28.0000")));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(aliceSource.get("id").asText())));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(bobSource.get("id").asText())));
    }

    @Test
    void failedValidationDoesNotConsumeTheKey() throws Exception {
        String token = registerAndLogin("Alice", "Recover");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "10.0000");

        transfer(token, "recover-key", transferJson(source, destination, "50.00", "EUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient balance."));

        transfer(token, "recover-key", transferJson(source, destination, "4.00", "EUR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(0, balance(source).compareTo(new BigDecimal("6.0000")));
        assertEquals(0, balance(destination).compareTo(new BigDecimal("4.0000")));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void anotherUserCannotReplaySomeoneElsesKey() throws Exception {
        String alice = registerAndLogin("Alice", "Owner");
        String bob = registerAndLogin("Bob", "Other");
        JsonNode source = createAccount(alice, "EUR");
        JsonNode destination = createAccount(alice, "EUR");
        fund(source, "50.0000");
        String body = transferJson(source, destination, "8.00", "EUR");

        MvcResult created = transfer(alice, "owned-key", body).andExpect(status().isCreated()).andReturn();
        String transferId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        transfer(bob, "owned-key", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Source account not found"));

        transfer(alice, "owned-key", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId));

        assertEquals(0, balance(source).compareTo(new BigDecimal("42.0000")));
        assertEquals(1, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    private org.springframework.test.web.servlet.ResultActions transfer(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private BigDecimal balance(JsonNode account) {
        return accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow().getBalance();
    }

    private void fund(JsonNode account, String amount) {
        transactionTemplate.executeWithoutResult(status -> {
            Account stored = accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow();
            stored.credit(new BigDecimal(amount));
        });
    }

    private String transferJson(JsonNode source, JsonNode destination, String amount, String currency) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s,"currency":"%s","status":"FAILED","id":"%s"}
                """.formatted(source.get("id").asText(), destination.get("id").asText(), amount, currency, UUID.randomUUID());
    }

    private JsonNode createAccount(String token, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerAndLogin(String firstName, String lastName) throws Exception {
        String address = "idemp-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"%s","lastName":"%s","email":"%s","password":"%s"}
                                """.formatted(firstName, lastName, address, PASSWORD)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(address, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }
}
