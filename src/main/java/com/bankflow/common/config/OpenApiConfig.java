package com.bankflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI bankFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BankFlow")
                        .version("0.1.0")
                        .description("""
                                Portfolio banking API. Money is stored as a decimal with scale 4.
                                Kafka delivery is at-least-once. Examples use fake values.
                                Authorize with the HTTP bearer scheme: Authorization: Bearer <token>.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/auth/login or POST /api/auth/register.")));
    }
}
