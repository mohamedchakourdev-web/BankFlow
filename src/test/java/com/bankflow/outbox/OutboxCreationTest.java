package com.bankflow.outbox;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboxCreationTest {

    private static final String PASSWORD = "SecurePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulTransferCreatesOnePendingOutboxEventInTheSameTransaction() throws Exception {
        String token = registerAndLogin("Outbox", "Create");
        JsonNode source = createAccount(token, "EUR");
        JsonNode destination = createAccount(token, "EUR");
        fund(source, "100.0000");

        MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "outbox-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":100.00,"currency":"EUR"}
                                """.formatted(source.get("id").asText(), destination.get("id").asText())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID transferId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        assertEquals(0, accountRepository.findById(UUID.fromString(source.get("id").asText())).orElseThrow()
                .getBalance().compareTo(new BigDecimal("0.0000")));
        assertEquals(0, accountRepository.findById(UUID.fromString(destination.get("id").asText())).orElseThrow()
                .getBalance().compareTo(new BigDecimal("100.0000")));
        assertEquals(1, outboxEventRepository.countByAggregateId(transferId));

        OutboxEvent event = outboxEventRepository.findAll().stream()
                .filter(candidate -> transferId.equals(candidate.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertEquals("TransferCompleted", event.getEventType());
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertEquals(1, payload.get("eventVersion").asInt());
        assertEquals("TransferCompleted", payload.get("eventType").asText());
        assertEquals(transferId.toString(), payload.get("transferId").asText());
        assertTrue(event.getPayload().contains("100.0000"));
        assertEquals(0, new BigDecimal(payload.get("amount").asText()).compareTo(new BigDecimal("100.0000")));
        assertEquals("EUR", payload.get("currency").asText());
        assertFalse(event.getPayload().contains("password"));
        assertFalse(event.getPayload().contains("fingerprint"));
        assertFalse(event.getPayload().toLowerCase().contains("idempotency"));
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
        String address = "outbox-" + UUID.randomUUID() + "@example.com";
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
