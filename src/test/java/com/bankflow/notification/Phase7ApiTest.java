package com.bankflow.notification;

import com.bankflow.account.Account;
import com.bankflow.account.AccountRepository;
import com.bankflow.audit.AuditActions;
import com.bankflow.audit.AuditLog;
import com.bankflow.audit.AuditLogRepository;
import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.Currency;
import com.bankflow.notification.NotificationRepository;
import com.bankflow.notification.NotificationService;
import com.bankflow.notification.NotificationType;
import com.bankflow.outbox.TransferCompletedEvent;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.service.TransferService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase7ApiTest {

    private static final String PASSWORD = "SecurePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status ->
                jdbcTemplate.update("delete from audit_logs where action = 'USER_LOGIN_FAILED'"));
    }

    private void clearFailedLogins() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status ->
                jdbcTemplate.update("delete from audit_logs where action = 'USER_LOGIN_FAILED'"));
    }

    @Test
    void loginAndAccountCreationAreAuditedWithoutSecrets() throws Exception {
        clearFailedLogins();
        String email = email();
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "WrongPassword123")))
                .andExpect(status().isUnauthorized());

        long failures = auditLogRepository.findAll().stream()
                .filter(log -> AuditActions.USER_LOGIN_FAILED.equals(log.getAction()))
                .count();
        assertEquals(2, failures);
        for (AuditLog failure : auditLogRepository.findAll()) {
            if (!AuditActions.USER_LOGIN_FAILED.equals(failure.getAction())) {
                continue;
            }
            assertNull(failure.getUser());
            assertFalse(failure.getMetadata().contains(PASSWORD));
            assertFalse(failure.getMetadata().contains("WrongPassword123"));
            assertFalse(failure.getMetadata().toLowerCase().contains(email.toLowerCase()));
            assertFalse(failure.getMetadata().contains("eyJ"));
        }

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        UUID userId = UUID.fromString(objectMapper.readTree(login.getResponse().getContentAsString()).get("user").get("id").asText());
        AuditLog success = auditLogRepository.findAll().stream()
                .filter(log -> AuditActions.USER_LOGIN_SUCCESS.equals(log.getAction()) && userId.equals(log.getEntityId()))
                .findFirst()
                .orElseThrow();
        assertFalse(success.getMetadata().contains(PASSWORD));
        assertFalse(success.getMetadata().contains(token));

        MvcResult account = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID accountId = UUID.fromString(objectMapper.readTree(account.getResponse().getContentAsString()).get("id").asText());
        assertEquals(1, auditLogRepository.countByActionAndEntityId(AuditActions.ACCOUNT_CREATED, accountId));
        AuditLog created = auditLogRepository.findAll().stream()
                .filter(log -> accountId.equals(log.getEntityId()))
                .findFirst()
                .orElseThrow();
        assertEquals("EUR", objectMapper.readTree(created.getMetadata()).get("currency").asText());
        assertFalse(created.getMetadata().contains(PASSWORD));
    }

    @Test
    void adminCanPageAuditLogsAndCustomersCannot() throws Exception {
        User admin = userRepository.save(User.createAdmin(email(), passwordEncoder.encode(PASSWORD), "Ada", "Admin"));
        String customerEmail = email();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Casey", "Customer", customerEmail)))
                .andExpect(status().isCreated());
        MvcResult customerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(customerEmail, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String customerToken = objectMapper.readTree(customerLogin.getResponse().getContentAsString()).get("token").asText();
        UUID customerId = UUID.fromString(objectMapper.readTree(customerLogin.getResponse().getContentAsString()).get("user").get("id").asText());
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(admin.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = objectMapper.readTree(adminLogin.getResponse().getContentAsString()).get("token").asText();

        AuditLog older = auditLogRepository.findAll().stream()
                .filter(log -> AuditActions.USER_REGISTERED.equals(log.getAction()) && customerId.equals(log.getEntityId()))
                .findFirst()
                .orElseThrow();
        AuditLog newer = auditLogRepository.findAll().stream()
                .filter(log -> AuditActions.USER_LOGIN_SUCCESS.equals(log.getAction()) && admin.getId().equals(log.getEntityId()))
                .findFirst()
                .orElseThrow();
        jdbcTemplate.update("update audit_logs set created_at = ? where id = ?", Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")), older.getId());
        jdbcTemplate.update("update audit_logs set created_at = ? where id = ?", Timestamp.from(Instant.parse("2099-01-02T00:00:00Z")), newer.getId());

        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$.content[0].metadata.password").doesNotExist());
    }

    @Test
    void notificationsAreOwnedPagedAndIdempotent() throws Exception {
        String senderToken = registerAndLogin("Nora", "Sender");
        String receiverToken = registerAndLogin("Owen", "Receiver");
        String otherToken = registerAndLogin("Pat", "Other");
        JsonNode source = createAccount(senderToken);
        JsonNode destination = createAccount(receiverToken);
        fund(source, "80.0000");
        UUID sourceId = UUID.fromString(source.get("id").asText());
        UUID destinationId = UUID.fromString(destination.get("id").asText());
        UUID senderId = accountRepository.findById(sourceId).orElseThrow().getUser().getId();

        var result = transferService.transfer(
                new AuthenticatedUser(senderId, Role.CUSTOMER),
                "phase7-note",
                new CreateTransferRequest(sourceId, destinationId, new BigDecimal("15.00"), "EUR")
        );
        TransferCompletedEvent event = eventFor(result.transfer().id(), sourceId, destinationId);
        notificationService.transferCompleted(event);
        notificationService.transferCompleted(event);

        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(senderId, NotificationType.TRANSFER_SENT, result.transfer().id()));
        UUID receiverId = accountRepository.findById(destinationId).orElseThrow().getUser().getId();
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(receiverId, NotificationType.TRANSFER_RECEIVED, result.transfer().id()));

        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER_SENT"))
                .andExpect(jsonPath("$.content[0].message").value("Transfer of 15.00 EUR completed."))
                .andExpect(jsonPath("$.content[0].read").value(false));
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + receiverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER_RECEIVED"))
                .andExpect(jsonPath("$.content[0].message").value("You received 15.00 EUR."));
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        String receiverNotificationId = notificationRepository.findByUser_Id(receiverId, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().get(0).getId().toString();
        mockMvc.perform(get("/api/notifications/" + receiverNotificationId).header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Notification not found"));
        mockMvc.perform(patch("/api/notifications/" + receiverNotificationId + "/read").header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isNotFound());

        String senderNotificationId = notificationRepository.findByUser_Id(senderId, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().get(0).getId().toString();
        mockMvc.perform(patch("/api/notifications/" + senderNotificationId + "/read").header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        notificationRepository.saveAndFlush(Notification.create(
                userRepository.findById(receiverId).orElseThrow(),
                NotificationType.TRANSFER_RECEIVED,
                "Older",
                "Older message",
                "TRANSFER",
                UUID.randomUUID()
        ));
        mockMvc.perform(patch("/api/notifications/read-all").header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0));
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + receiverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.read == false)]").exists());
    }

    @Test
    void sameUserTransferCreatesOneNotificationAndPagesNewestFirst() throws Exception {
        String token = registerAndLogin("Sam", "Owner");
        JsonNode first = createAccount(token);
        JsonNode second = createAccount(token);
        fund(first, "50.0000");
        UUID firstId = UUID.fromString(first.get("id").asText());
        UUID secondId = UUID.fromString(second.get("id").asText());
        UUID userId = accountRepository.findById(firstId).orElseThrow().getUser().getId();
        var result = transferService.transfer(
                new AuthenticatedUser(userId, Role.CUSTOMER),
                "phase7-self",
                new CreateTransferRequest(firstId, secondId, new BigDecimal("5.00"), "EUR")
        );
        notificationService.transferCompleted(eventFor(result.transfer().id(), firstId, secondId));
        assertEquals(1, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(userId, NotificationType.TRANSFER_SENT, result.transfer().id()));
        assertEquals(0, notificationRepository.countByUser_IdAndTypeAndRelatedEntityId(userId, NotificationType.TRANSFER_RECEIVED, result.transfer().id()));

        Notification older = notificationRepository.findByUser_Id(userId, org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0);
        Notification newer = notificationRepository.saveAndFlush(Notification.create(
                userRepository.findById(userId).orElseThrow(),
                NotificationType.TRANSFER_RECEIVED,
                "Newer",
                "You received 1.00 EUR.",
                "TRANSFER",
                UUID.randomUUID()
        ));
        jdbcTemplate.update("update notifications set created_at = ? where id = ?", Timestamp.from(Instant.parse("2098-01-01T00:00:00Z")), older.getId());
        jdbcTemplate.update("update notifications set created_at = ? where id = ?", Timestamp.from(Instant.parse("2098-01-02T00:00:00Z")), newer.getId());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private TransferCompletedEvent eventFor(UUID transferId, UUID sourceId, UUID destinationId) {
        return new TransferCompletedEvent(
                UUID.randomUUID(),
                TransferCompletedEvent.TYPE,
                TransferCompletedEvent.VERSION,
                transferId,
                sourceId,
                destinationId,
                new BigDecimal("1.0000"),
                Currency.EUR,
                Instant.parse("2026-09-28T00:00:00Z")
        );
    }

    private void fund(JsonNode account, String amount) {
        Account stored = accountRepository.findById(UUID.fromString(account.get("id").asText())).orElseThrow();
        stored.credit(new BigDecimal(amount));
        accountRepository.saveAndFlush(stored);
    }

    private JsonNode createAccount(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerAndLogin(String firstName, String lastName) throws Exception {
        String address = email();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(firstName, lastName, address)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(address, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String registerJson(String email) {
        return registerJson("Ada", "Customer", email);
    }

    private String registerJson(String firstName, String lastName, String email) {
        return """
                {"firstName":"%s","lastName":"%s","email":"%s","password":"%s"}
                """.formatted(firstName, lastName, email, PASSWORD);
    }

    private String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private String email() {
        return "phase7-" + UUID.randomUUID() + "@example.com";
    }
}
