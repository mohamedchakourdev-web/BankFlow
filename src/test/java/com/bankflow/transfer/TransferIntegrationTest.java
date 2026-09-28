package com.bankflow.transfer;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.JwtService;
import com.bankflow.transfer.repository.TransferRepository;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransferIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulTransferMovesMoneyAndPersistsCompletedRecord() throws Exception {
        String aliceToken = registerAndLogin("Alice", "Sender");
        String bobToken = registerAndLogin("Bob", "Receiver");
        JsonNode source = createAccount(aliceToken, "EUR");
        JsonNode destination = createAccount(bobToken, "EUR");
        fund(source, "1000.0000");
        fund(destination, "300.0000");

        MvcResult result = mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId":"%s",
                                  "destinationAccountId":"%s",
                                  "amount":100.00,
                                  "currency":"EUR",
                                  "status":"FAILED",
                                  "senderUserId":"%s",
                                  "balance":500000
                                }
                                """.formatted(source.get("id").asText(), destination.get("id").asText(), UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.sourceAccountId").value(source.get("id").asText()))
                .andExpect(jsonPath("$.destinationAccountId").value(destination.get("id").asText()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal(body.get("amount").asText()).compareTo(new BigDecimal("100.0000")));

        Transfer stored = transferRepository.findById(UUID.fromString(body.get("id").asText())).orElseThrow();
        assertEquals(TransferStatus.COMPLETED, stored.getStatus());
        assertEquals(0, stored.getAmount().compareTo(new BigDecimal("100.0000")));
        assertEquals(UUID.fromString(source.get("id").asText()), stored.getSourceAccount().getId());
        assertEquals(UUID.fromString(destination.get("id").asText()), stored.getDestinationAccount().getId());
        assertNotNull(stored.getReference());
        assertEquals(1, transferRepository.countBySourceAccount_Id(stored.getSourceAccount().getId()));

        assertBalance(aliceToken, source, "900.0000");
        assertBalance(bobToken, destination, "400.0000");
    }

    @Test
    void currencyCanBeOmittedWhenBothAccountsMatch() throws Exception {
        String token = registerAndLogin("Same", "Currency");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "50.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00}
                                """.formatted(source.get("id").asText(), destination.get("id").asText())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertBalance(token, source, "40.0000");
        assertBalance(token, destination, "10.0000");
    }

    @Test
    void customerCannotTransferFromAnotherCustomersAccount() throws Exception {
        String aliceToken = registerAndLogin("Alice", "Owner");
        String bobToken = registerAndLogin("Bob", "Intruder");
        JsonNode aliceAccount = createAccount(aliceToken, "EUR");
        JsonNode bobAccount = createAccount(bobToken, "EUR");
        fund(aliceAccount, "100.0000");
        fund(bobAccount, "20.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(aliceAccount, bobAccount, "10.00", "EUR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Source account not found"));

        assertBalance(aliceToken, aliceAccount, "100.0000");
        assertBalance(bobToken, bobAccount, "20.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(aliceAccount.get("id").asText())));
    }

    @Test
    void adminCannotDebitCustomerAccount() throws Exception {
        String customerToken = registerAndLogin("Customer", "Funds");
        JsonNode source = createAccount(customerToken, "EUR");
        JsonNode destination = createAccount(customerToken, "EUR");
        fund(source, "100.0000");

        User admin = userRepository.save(User.createAdmin(
                email(),
                passwordEncoder.encode(PASSWORD),
                "Ada",
                "Admin"
        ));
        String adminToken = jwtService.generateToken(admin.getId(), Role.ADMIN);

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "25.00", "EUR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Source account not found"));

        assertBalance(customerToken, source, "100.0000");
        assertBalance(customerToken, destination, "0.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void insufficientBalanceIsRejectedAndBalancesStayUnchanged() throws Exception {
        String token = registerAndLogin("Poor", "Balance");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "100.0000");
        fund(destination, "50.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "150.00", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient balance."));

        assertBalance(token, source, "100.0000");
        assertBalance(token, destination, "50.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void zeroAmountIsRejected() throws Exception {
        String token = registerAndLogin("Zero", "Amount");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "40.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "0", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'amount')].message")
                        .value("Amount must be greater than zero"));

        assertBalance(token, source, "40.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void negativeAmountIsRejected() throws Exception {
        String token = registerAndLogin("Negative", "Amount");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "40.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "-5.00", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'amount')].message")
                        .value("Amount must be greater than zero"));

        assertBalance(token, source, "40.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void selfTransferIsRejected() throws Exception {
        String token = registerAndLogin("Self", "Transfer");
        JsonNode source = createAccount(token, "EUR");
        fund(source, "80.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00,"currency":"EUR"}
                                """.formatted(source.get("id").asText(), source.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Source and destination accounts must be different"));

        assertBalance(token, source, "80.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        String token = registerAndLogin("Mixed", "Currency");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "USD");
        fund(source, "70.0000");
        fund(destination, "15.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "10.00", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Currencies must match"));

        assertBalance(token, source, "70.0000");
        assertBalance(token, destination, "15.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void requestedCurrencyMustMatchTheAccounts() throws Exception {
        String token = registerAndLogin("Request", "Currency");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "70.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "10.00", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Currencies must match"));

        assertBalance(token, source, "70.0000");
        assertBalance(token, destination, "0.0000");
    }

    @Test
    void unknownSourceAccountIsRejected() throws Exception {
        String token = registerAndLogin("Missing", "Source");
        JsonNode destination = createAccount(token, "EUR");
        fund(destination, "25.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00,"currency":"EUR"}
                                """.formatted(UUID.randomUUID(), destination.get("id").asText())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Source account not found"));

        assertBalance(token, destination, "25.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(destination.get("id").asText())));
    }

    @Test
    void unknownDestinationAccountIsRejected() throws Exception {
        String token = registerAndLogin("Missing", "Destination");
        JsonNode source = createAccount(token, "EUR");
        fund(source, "25.0000");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00,"currency":"EUR"}
                                """.formatted(source.get("id").asText(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Destination account not found"));

        assertBalance(token, source, "25.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
    }

    @Test
    void unauthenticatedTransferIsRejected() throws Exception {
        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":10.00,"currency":"EUR"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void invalidUuidIsRejected() throws Exception {
        String token = registerAndLogin("Bad", "Uuid");

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"not-a-uuid","destinationAccountId":"%s","amount":10.00,"currency":"EUR"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request contains an invalid UUID"));
    }

    @Test
    void inactiveAccountsAreRejected() throws Exception {
        String token = registerAndLogin("Frozen", "Account");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        JsonNode other = createAccount(token, "EUR");
        fund(source, "30.0000");
        fund(destination, "5.0000");
        fund(other, "5.0000");
        freeze(source);

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, destination, "5.00", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Source account is not active"));

        freeze(destination);

        mockMvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(other, destination, "1.00", "EUR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination account is not active"));

        assertBalance(token, source, "30.0000");
        assertBalance(token, destination, "5.0000");
        assertBalance(token, other, "5.0000");
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(source.get("id").asText())));
        assertEquals(0, transferRepository.countBySourceAccount_Id(UUID.fromString(other.get("id").asText())));
    }

    @Test
    void transferHistoryIsNotAvailableYet() throws Exception {
        String token = registerAndLogin("No", "History");

        mockMvc.perform(get("/api/transfers").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Request method is not supported"));
    }

    private void assertBalance(String token, JsonNode account, String expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/" + account.get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal(body.get("balance").asText()).compareTo(new BigDecimal(expected)));
    }

    private void fund(JsonNode account, String amount) {
        Account stored = accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow();
        stored.credit(new BigDecimal(amount));
        accountRepository.saveAndFlush(stored);
    }

    private void freeze(JsonNode account) {
        Account stored = accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow();
        stored.freeze();
        accountRepository.saveAndFlush(stored);
    }

    private String transferJson(JsonNode source, JsonNode destination, String amount, String currency) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s,"currency":"%s"}
                """.formatted(source.get("id").asText(), destination.get("id").asText(), amount, currency);
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
        String address = email();
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

    private String email() {
        return "xfer-" + UUID.randomUUID() + "@example.com";
    }
}
