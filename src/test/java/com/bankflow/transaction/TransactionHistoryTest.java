package com.bankflow.transaction;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.auth.security.JwtService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.bankflow.transfer.CapturingStatementInspector")
class TransactionHistoryTest {

    private static final String PASSWORD = "SecurePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        com.bankflow.transfer.CapturingStatementInspector.SQL.clear();
    }

    @Test
    void senderAndReceiverSeeTheTransferAndAnUnrelatedCustomerDoesNot() throws Exception {
        String alice = registerAndLogin("Alice", "Sender");
        String bob = registerAndLogin("Bob", "Receiver");
        String carol = registerAndLogin("Carol", "Outsider");
        JsonNode source = createAccount(alice, "EUR");
        JsonNode destination = createAccount(bob, "EUR");
        fund(source, "100.0000");

        String transferId = transfer(alice, source, destination, "25.00");

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(transferId))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].sourceAccountId").value(source.get("id").asText()))
                .andExpect(jsonPath("$.content[0].destinationAccountId").value(destination.get("id").asText()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[0].fingerprint").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(transferId));

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        mockMvc.perform(get("/api/transactions/" + transferId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get("/api/transactions/" + transferId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId));

        mockMvc.perform(get("/api/transactions/" + transferId).header("Authorization", "Bearer " + carol))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transaction not found"));
    }

    @Test
    void adminCanReadAnyTransactionAndCustomersCannotChangeHistory() throws Exception {
        String alice = registerAndLogin("Alice", "Visible");
        JsonNode source = createAccount(alice, "EUR");
        JsonNode destination = createAccount(alice, "EUR");
        fund(source, "40.0000");
        String transferId = transfer(alice, source, destination, "5.00");

        User admin = userRepository.save(User.createAdmin(email(), passwordEncoder.encode(PASSWORD), "Ada", "Admin"));
        String adminToken = jwtService.generateToken(admin.getId(), Role.ADMIN);

        mockMvc.perform(get("/api/transactions/" + transferId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + transferId + "')]").exists());

        mockMvc.perform(put("/api/transactions/" + transferId)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"amount\":1}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/transactions/" + transferId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void paginationIsBoundedAndNewestComesFirst() throws Exception {
        String token = registerAndLogin("Page", "Owner");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "100.0000");
        String oldest = transfer(token, source, destination, "1.00");
        String middle = transfer(token, source, destination, "1.00");
        String newest = transfer(token, source, destination, "1.00");
        stamp(oldest, Instant.parse("2026-01-01T00:00:00Z"));
        stamp(middle, Instant.parse("2026-01-02T00:00:00Z"));
        stamp(newest, Instant.parse("2026-01-03T00:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].id").value(newest))
                .andExpect(jsonPath("$.content[2].id").value(oldest));

        mockMvc.perform(get("/api/transactions?page=1&size=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(middle));

        mockMvc.perform(get("/api/transactions?size=101").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must be between 1 and 100"));
    }

    @Test
    void customerCanFilterOnlyTransactionsInvolvingTheirOwnAccount() throws Exception {
        String alice = registerAndLogin("Alice", "Filter");
        String bob = registerAndLogin("Bob", "Filter");
        JsonNode aliceSource = createAccount(alice, "EUR");
        JsonNode bobAccount = createAccount(bob, "EUR");
        fund(aliceSource, "20.0000");
        String transferId = transfer(alice, aliceSource, bobAccount, "3.00");

        mockMvc.perform(get("/api/transactions?accountId=" + aliceSource.get("id").asText())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(transferId));

        mockMvc.perform(get("/api/transactions?accountId=" + bobAccount.get("id").asText())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(transferId));

        mockMvc.perform(get("/api/transactions?accountId=" + aliceSource.get("id").asText())
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        JsonNode outsider = createAccount(registerAndLogin("Cara", "Filter"), "EUR");
        String outsiderToken = registerAndLogin("Dan", "Filter");
        mockMvc.perform(get("/api/transactions?accountId=" + aliceSource.get("id").asText())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/transactions?accountId=" + outsider.get("id").asText())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void unknownAndUnauthenticatedTransactionRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/transactions/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        String token = registerAndLogin("Missing", "History");
        mockMvc.perform(get("/api/transactions/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transaction not found"));
    }

    @Test
    void historyQueryFiltersOwnershipInTheDatabase() throws Exception {
        String token = registerAndLogin("Query", "Shape");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "15.0000");
        transfer(token, source, destination, "2.00");
        entityManager.flush();
        entityManager.clear();
        com.bankflow.transfer.CapturingStatementInspector.SQL.clear();

        mockMvc.perform(get("/api/transactions?page=0&size=20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        String sql = String.join("\n", com.bankflow.transfer.CapturingStatementInspector.SQL).toLowerCase();
        assertTrue(sql.contains("user_id"), sql);
        assertTrue(sql.contains("fetch first") || sql.contains("limit"), sql);
        long transferReads = com.bankflow.transfer.CapturingStatementInspector.SQL.stream()
                .filter(statement -> statement.toLowerCase().contains("from transfers"))
                .count();
        assertTrue(transferReads <= 3, sql);
    }

    private void stamp(String transferId, Instant createdAt) {
        jdbcTemplate.update(
                "update transfers set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                UUID.fromString(transferId)
        );
    }

    private String transfer(String token, JsonNode source, JsonNode destination, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s,"currency":"EUR"}
                                """.formatted(source.get("id").asText(), destination.get("id").asText(), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void fund(JsonNode account, String amount) {
        Account stored = accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow();
        stored.credit(new BigDecimal(amount));
        accountRepository.saveAndFlush(stored);
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
        return "hist-" + UUID.randomUUID() + "@example.com";
    }
}
