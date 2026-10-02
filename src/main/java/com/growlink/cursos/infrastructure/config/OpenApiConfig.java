package com.growlink.cursos.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Mismo patron que usuarios-service: boton "Authorize" en Swagger UI para
// pegar el JWT (emitido por auth-service, validado aqui con el mismo
// GROWLINK_JWT_SECRET compartido).
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cursosServiceOpenAPI() {
        final String jwtScheme = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("cursos-service API")
                        .description("Cursos, prerequisitos, habilidades y roadmap con IA - GrowLink")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(jwtScheme))
                .components(new Components()
                        .addSecuritySchemes(jwtScheme, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
