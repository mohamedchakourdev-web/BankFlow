package com.bankflow.auth;

import com.bankflow.auth.security.JwtIdentity;
import com.bankflow.auth.security.JwtService;
import com.bankflow.auth.service.AuthService;
import com.bankflow.user.Role;
import com.bankflow.user.User;
import com.bankflow.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("BankFlow"));
    }

    @Test
    void registrationSucceedsAndNeverReturnsPassword() throws Exception {
        String email = email();
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("John", "Doe", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.firstName").value("John"))
                .andExpect(jsonPath("$.user.lastName").value("Doe"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(PASSWORD));
        assertFalse(body.contains("$2a$"));
        assertFalse(body.contains("$2b$"));

        User saved = userRepository.findByEmail(email).orElseThrow();
        assertNotEquals(PASSWORD, saved.getPassword());
        assertTrue(saved.getPassword().startsWith("$2"));
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPassword()));
        org.junit.jupiter.api.Assertions.assertEquals(Role.CUSTOMER, saved.getRole());
    }

    @Test
    void registrationNormalizesEmailAndIgnoresAdminRole() throws Exception {
        String email = email();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "  Jane  ",
                                  "lastName": "  Doe  ",
                                  "email": "  %s  ",
                                  "password": "%s",
                                  "role": "ADMIN"
                                }
                                """.formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.firstName").value("Jane"))
                .andExpect(jsonPath("$.user.lastName").value("Doe"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        User saved = userRepository.findByEmail(email).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Role.CUSTOMER, saved.getRole());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = email();
        register(email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("John", "Doe", email, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(AuthService.EMAIL_ALREADY_REGISTERED))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void invalidEmailIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("John", "Doe", "not-an-email", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')].message").value("Email must be valid"));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("  ", "Doe", email(), PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'firstName')].message").value("First name is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "longpassword", "12345678"})
    void weakPasswordIsRejected(String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("John", "Doe", email(), password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists())
                .andExpect(content().string(not(containsString(password))));
    }

    @Test
    void loginSucceedsAndTokenCarriesIdentity() throws Exception {
        String email = email();
        register(email);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JwtIdentity identity = jwtService.parse(body.get("token").asText());
        org.junit.jupiter.api.Assertions.assertEquals(UUID.fromString(body.get("user").get("id").asText()), identity.userId());
        org.junit.jupiter.api.Assertions.assertEquals(Role.CUSTOMER, identity.role());
        org.junit.jupiter.api.Assertions.assertNotNull(identity.issuedAt());
        assertTrue(identity.expiresAt().isAfter(identity.issuedAt()));
    }

    @Test
    void invalidPasswordAndUnknownEmailReturnTheSameError() throws Exception {
        String email = email();
        register(email);

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "WrongPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(AuthService.INVALID_CREDENTIALS))
                .andExpect(content().string(not(containsString("BCrypt"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AuthService.INVALID_CREDENTIALS))
                .andReturn();

        JsonNode wrong = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode unknown = objectMapper.readTree(unknownEmail.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(wrong.get("status").asInt(), unknown.get("status").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(wrong.get("message").asText(), unknown.get("message").asText());
    }

    @Test
    void meRequiresAValidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required."));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token."))
                .andExpect(content().string(not(containsString("JwtException"))));

        String forged = io.jsonwebtoken.Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "CUSTOMER")
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "different-secret-key-at-least-32-chars".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token."));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String email = email();
        String userId = register(email).get("user").get("id").asText();
        String token = jwtService.issue(
                UUID.fromString(userId),
                Role.CUSTOMER,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60)
        );

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired token."));
    }

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        String email = email();
        JsonNode registered = register(email);
        String token = registered.get("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registered.get("user").get("id").asText()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    void futureApiRoutesRequireAuthenticationAndAdminRoutesRequireAdmin() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());

        JsonNode registered = register(email());
        String token = registered.get("token").asText();

        mockMvc.perform(get("/api/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."));
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("John", "Doe", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerJson(String firstName, String lastName, String email, String password) {
        return """
                {"firstName":"%s","lastName":"%s","email":"%s","password":"%s"}
                """.formatted(firstName, lastName, email, password);
    }

    private String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
