package com.bankflow.common;

import com.bankflow.common.response.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@Tag(name = "Health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final String kafkaBootstrapServers;

    public HealthController(@Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    @GetMapping("/health")
    @SecurityRequirements
    @Operation(summary = "Application health", description = "Process is up. Does not check PostgreSQL or Kafka.")
    public HealthResponse health() {
        return new HealthResponse("UP", "BankFlow");
    }

    @GetMapping("/health/kafka")
    @SecurityRequirements
    @Operation(summary = "Kafka availability", description = "Informational. A DOWN result does not mean transfers cannot be accepted.")
    public Map<String, String> kafka() {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "500",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "500",
                AdminClientConfig.RETRIES_CONFIG, "0",
                "socket.connection.setup.timeout.ms", "500",
                "socket.connection.setup.timeout.max.ms", "500"
        );
        try (AdminClient client = AdminClient.create(config)) {
            client.describeCluster().clusterId().get(500, TimeUnit.MILLISECONDS);
            return Map.of("status", "UP", "component", "kafka");
        } catch (Exception ex) {
            log.warn("Kafka availability check failed ({})", ex.getClass().getSimpleName());
            return Map.of("status", "DOWN", "component", "kafka");
        }
    }
}
