package com.bankflow.account;

import com.bankflow.auth.security.JwtService;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountIntegrationTest {

    private static final String PASSWORD = "SecurePassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AccountRepository accountRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerCreatesAnAccountOwnedByThemselves() throws Exception {
        String token = registerAndLogin("Ada", "Owner");

        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"eur","userId":"%s","balance":1000,"accountNumber":"000000000001"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.accountNumber").exists())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(PASSWORD))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("$2a$"))))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accountNumber = body.get("accountNumber").asText();
        assertTrue(accountNumber.matches("[1-9][0-9]{11}"));
        assertEquals(0, new BigDecimal(body.get("balance").asText()).compareTo(BigDecimal.ZERO));

        Account stored = accountRepository.findById(UUID.fromString(body.get("id").asText())).orElseThrow();
        assertEquals(0, stored.getBalance().compareTo(BigDecimal.ZERO));
        assertEquals(currentUserId(token), stored.getUser().getId());
        assertEquals(accountNumber, stored.getAccountNumber());
    }

    @Test
    void accountNumbersAreUnique() throws Exception {
        String token = registerAndLogin("Num", "Ber");
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            numbers.add(createAccount(token, "USD").get("accountNumber").asText());
        }
        assertEquals(5, numbers.size());
    }

    @Test
    void customerListsAndReadsOnlyTheirOwnAccounts() throws Exception {
        String ownerToken = registerAndLogin("Owner", "One");
        String otherToken = registerAndLogin("Other", "Two");
        JsonNode own = createAccount(ownerToken, "EUR");
        JsonNode foreign = createAccount(otherToken, "GBP");

        mockMvc.perform(get("/api/accounts").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(own.get("id").asText()))
                .andExpect(jsonPath("$[0].accountNumber").value(own.get("accountNumber").asText()))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(foreign.get("accountNumber").asText()))));

        mockMvc.perform(get("/api/accounts/" + own.get("id").asText())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.get("id").asText()))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get("/api/accounts/" + foreign.get("id").asText())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found"));
    }

    @Test
    void unknownAccountAndInvalidIdAreRejected() throws Exception {
        String token = registerAndLogin("Missing", "Account");

        mockMvc.perform(get("/api/accounts/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Account not found"));

        mockMvc.perform(get("/api/accounts/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Account id must be a valid UUID"));
    }

    @Test
    void invalidCurrencyIsRejected() throws Exception {
        String token = registerAndLogin("Bad", "Currency");

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'currency')].message")
                        .value("Currency must be USD, EUR, or GBP"));

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'currency')].message")
                        .value("Currency is required"));
    }

    @Test
    void unauthenticatedAccountRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanInspectAnotherCustomersAccount() throws Exception {
        String customerToken = registerAndLogin("Customer", "Visible");
        JsonNode account = createAccount(customerToken, "EUR");

        String adminEmail = email();
        User admin = userRepository.save(User.createAdmin(
                adminEmail,
                passwordEncoder.encode(PASSWORD),
                "Ada",
                "Admin"
        ));
        String adminToken = jwtService.generateToken(admin.getId(), Role.ADMIN);

        mockMvc.perform(get("/api/accounts").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + account.get("id").asText() + "')]").exists());

        mockMvc.perform(get("/api/accounts/" + account.get("id").asText())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(account.get("accountNumber").asText()))
                .andExpect(jsonPath("$.password").doesNotExist());
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

    private UUID currentUserId(String token) {
        return jwtService.parse(token).userId();
    }

    private String email() {
        return "acct-" + UUID.randomUUID() + "@example.com";
    }
}
