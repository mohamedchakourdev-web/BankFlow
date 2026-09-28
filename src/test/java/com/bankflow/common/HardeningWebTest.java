package com.bankflow.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HardeningWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationHealthStaysUpWithoutKafka() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("BankFlow"))
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    @Test
    void readinessDependsOnTheDatabaseAndNotKafka() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.kafka").doesNotExist());
    }

    @Test
    void suppliedCorrelationIdIsEchoed() throws Exception {
        mockMvc.perform(get("/api/health").header(CorrelationIdFilter.HEADER, "phase8-request-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "phase8-request-01"));
    }

    @Test
    void oversizedCorrelationIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/health").header(CorrelationIdFilter.HEADER, "x".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("X-Correlation-Id")));
    }

    @Test
    void openApiDocumentsBearerAuthAndIdempotency() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/transfers'].post.parameters[?(@.name == 'Idempotency-Key')]").exists())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.requestBody").exists())
                .andExpect(jsonPath("$.paths['/api/accounts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/transactions'].get").exists())
                .andExpect(jsonPath("$.paths['/api/notifications'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/audit-logs'].get").exists())
                .andExpect(jsonPath("$.info.description").value(not(org.hamcrest.Matchers.containsString("change_me"))));
    }

    @Test
    void businessRoutesStayAuthenticated() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void kafkaAvailabilityIsSeparateFromApplicationHealth() throws Exception {
        mockMvc.perform(get("/api/health/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.component").value("kafka"))
                .andExpect(jsonPath("$.status").value(anyOf(equalTo("UP"), equalTo("DOWN"))));
    }

    @Test
    @WithMockUser
    void notificationUuidUsesItsOwnMessage() throws Exception {
        mockMvc.perform(get("/api/notifications/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Notification id must be a valid UUID"));
    }
}
