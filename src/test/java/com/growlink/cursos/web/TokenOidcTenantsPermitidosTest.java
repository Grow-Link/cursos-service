package com.growlink.cursos.web;

import com.growlink.cursos.web.LlavesYTokensDePrueba.Datos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO seguridad: multi-inquilino CON lista de inquilinos permitidos (AUTH_ALLOWED_TENANTS). Es la variante
// que se usaria para una prueba piloto o un despliegue para unas organizaciones concretas: la app sigue siendo
// multi-inquilino en Entra (el emisor viene con el inquilino de cada quien), pero el servicio solo acepta los de
// la lista. Va en su propia clase porque la lista es configuracion de arranque.
//
// Esta en el mismo paquete que TokenOidcMultiTenantTest (que prueba la lista vacia, o sea "cualquier inquilino")
// y comparte con el sus llaves RSA generadas en la prueba: aqui tampoco hay conexion a Microsoft.
@SpringBootTest(properties = {
        "CLAUDE_API_KEY=",
        "auth.mode=oidc",
        "auth.multi-tenant=true",
        // esta prueba es de la VALIDACION del token, no de como se resuelve la identidad: el
        // usuarioId y el rol se leen de los claims, como antes de que existiera la consulta a usuarios-service
        "auth.identity-source=claims",
        "auth.oidc.audience=api://growlink-cursos",
        "auth.oidc.allowed-tenants=" + TokenOidcTenantsPermitidosTest.INQUILINO_INVITADO,
        "auth.oidc.jwk-set-uri=http://127.0.0.1:1/jwks-que-no-se-consulta"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosoidcpermitidos;MODE=PostgreSQL")
@Import(TokenOidcTenantsPermitidosTest.DecodificadorConLaLlaveDeLaPrueba.class)
class TokenOidcTenantsPermitidosTest {

    static final String INQUILINO_INVITADO = "11111111-2222-3333-4444-555555555555";
    private static final String INQUILINO_DE_FUERA = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String AUDIENCIA = "api://growlink-cursos";

    @TestConfiguration
    static class DecodificadorConLaLlaveDeLaPrueba {
        @Bean
        @Primary
        JwtDecoder decodificadorDePrueba(OAuth2TokenValidator<Jwt> validadorDeVerdad) {
            return LlavesYTokensDePrueba.decodificadorCon(validadorDeVerdad);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unInquilinoDeLaListaEntra() throws Exception {
        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + Datos.de(INQUILINO_INVITADO, AUDIENCIA).firmar()))
                .andExpect(status().isOk());
    }

    @Test
    void unInquilinoQueNoEstaEnLaListaNoEntraAunqueSuTokenSeaImpecable() throws Exception {
        // token de Entra de verdad: bien firmado, vigente, con el emisor y el tid correctos y para esta
        // aplicacion. Lo unico que le pasa es que su organizacion no esta invitada
        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + Datos.de(INQUILINO_DE_FUERA, AUDIENCIA).firmar()))
                .andExpect(status().isUnauthorized());
    }
}
