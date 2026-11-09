package com.growlink.cursos.web;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO seguridad: el modo auth.mode=oidc, en el que el token lo emite y firma el proveedor de identidad
// (Microsoft Entra ID) y este servicio solo lo valida como resource server.
//
// La prueba NO se conecta a Microsoft: genera su propio par de llaves RSA, firma los tokens con la privada y le
// da al servicio un decodificador que verifica con la publica. Las REGLAS que se comprueban son las de verdad:
// el bean validadorDeTokenOidc de AutenticacionOidcConfig, el mismo que se usaria en produccion (vigencia,
// emisor y audiencia). Lo unico que se reemplaza es de donde salen las llaves publicas.
//
// Los seis casos: token valido, vencido, de otro emisor, para otra aplicacion, mal firmado y ausente. Y uno mas
// que importa al hacer el cambio: el token viejo del secreto compartido ya no sirve en este modo.
@SpringBootTest(properties = {
        "CLAUDE_API_KEY=",
        "auth.mode=oidc",
        // esta prueba es de la VALIDACION del token, no de como se resuelve la identidad: el
        // usuarioId y el rol se leen de los claims, como antes de que existiera la consulta a usuarios-service
        "auth.identity-source=claims",
        "auth.oidc.issuer-uri=" + TokenOidcTest.EMISOR,
        "auth.oidc.audience=" + TokenOidcTest.AUDIENCIA,
        // nunca se consulta: el decodificador de la prueba trae la llave publica puesta
        "auth.oidc.jwk-set-uri=http://127.0.0.1:1/jwks-que-no-se-consulta"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosoidc;MODE=PostgreSQL")
@Import(TokenOidcTest.DecodificadorConLaLlaveDeLaPrueba.class)
class TokenOidcTest {

    static final String EMISOR = "https://login.microsoftonline.com/inquilino-de-prueba/v2.0";
    static final String AUDIENCIA = "api://growlink-cursos";
    private static final long USUARIO = 7777L;

    // el par de llaves de la prueba: hace de proveedor de identidad, sin red
    private static final KeyPair PAR = generar();
    private static final KeyPair OTRO_PAR = generar();

    private static KeyPair generar() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo generar el par de llaves de la prueba", e);
        }
    }

    // Reemplaza SOLO el origen de las llaves publicas (en produccion las trae del emisor por HTTPS) y deja
    // intactas las reglas de validacion, que se piden al contexto tal como estan configuradas.
    @TestConfiguration
    static class DecodificadorConLaLlaveDeLaPrueba {
        @Bean
        @Primary
        JwtDecoder decodificadorDePrueba(OAuth2TokenValidator<Jwt> validadorDeVerdad) {
            NimbusJwtDecoder decodificador = NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) PAR.getPublic()).build();
            decodificador.setJwtValidator(validadorDeVerdad);
            return decodificador;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ el token que si vale

    @Test
    void unTokenBienFirmadoDelEmisorYLaAudienciaCorrectosEntra() throws Exception {
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + tokenValido()))
                .andExpect(status().isOk());
    }

    @Test
    void elUsuarioDelTokenLlegaALaAplicacionComoSiempre() throws Exception {
        // 404 (no tiene roadmap), NO 401 ni 403: se autentico y ademas se le reconocio como el usuario 7777, que
        // es lo que necesitan AuthenticatedUser y los controllers (ver ConvertidorDeTokenOidc)
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", String.valueOf(USUARIO))
                        .header("Authorization", "Bearer " + tokenValido()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ los que no

    @Test
    void sinTokenSeResponde401() throws Exception {
        var respuesta = mockMvc.perform(get("/api/cursos")).andExpect(status().isUnauthorized())
                .andReturn().getResponse();
        // y se le dice al cliente como tiene que autenticarse
        assertThat(respuesta.getHeader("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void unTokenVencidoSeResponde401() throws Exception {
        String vencido = construir(PAR.getPrivate(), EMISOR, AUDIENCIA,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + vencido))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenDeOtroEmisorSeResponde401() throws Exception {
        String deOtro = construir(PAR.getPrivate(), "https://emisor-que-no-es-el-nuestro.example/v2.0",
                AUDIENCIA, Instant.now(), Instant.now().plusSeconds(3600));
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + deOtro))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenParaOtraAplicacionDelMismoEmisorSeResponde401() throws Exception {
        // mismo proveedor de identidad, firma buena, vigente... pero emitido para OTRA aplicacion
        String paraOtra = construir(PAR.getPrivate(), EMISOR, "api://otra-aplicacion-del-inquilino",
                Instant.now(), Instant.now().plusSeconds(3600));
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + paraOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenConFirmaInvalidaSeResponde401() throws Exception {
        // todos los claims correctos, pero firmado con otra llave: no lo emitio quien dice
        String malFirmado = construir(OTRO_PAR.getPrivate(), EMISOR, AUDIENCIA,
                Instant.now(), Instant.now().plusSeconds(3600));
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + malFirmado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enModoOidcElTokenViejoDelSecretoCompartidoYaNoSirve() throws Exception {
        SecretKey secreto = Keys.hmacShaKeyFor(
                "test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));
        String tokenLegacy = Jwts.builder().subject(String.valueOf(USUARIO)).claim("rol", "USUARIO")
                .signWith(secreto).compact();

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + tokenLegacy))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ ayudas

    private static String tokenValido() {
        return construir(PAR.getPrivate(), EMISOR, AUDIENCIA, Instant.now(), Instant.now().plusSeconds(3600));
    }

    // un token con la forma que tendria el del proveedor de identidad: sub opaco (como el de Entra ID) y el id
    // numerico de GrowLink en un claim de la aplicacion
    private static String construir(PrivateKey llave, String emisor, String audiencia, Instant desde, Instant hasta) {
        return Jwts.builder()
                .subject("AAAAAAAAAAAAAAAAAAAAAIk_bMBSKsTxQ4Y-eV3VvQ4")
                .issuer(emisor)
                .audience().add(audiencia).and()
                .issuedAt(Date.from(desde))
                .expiration(Date.from(hasta))
                .claim("growlink_uid", USUARIO)
                .claim("roles", java.util.List.of("USUARIO"))
                .signWith(llave, Jwts.SIG.RS256)
                .compact();
    }
}
