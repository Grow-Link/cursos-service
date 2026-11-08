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

// ATRIBUTO seguridad: el modo oidc con la app registrada en Microsoft Entra ID como MULTI-INQUILINO (cuentas de
// cualquier directorio organizacional y cuentas personales de Microsoft). Ahi el `iss` del token trae el
// inquilino de quien inicia sesion, asi que comparar contra un emisor fijo dejaria fuera a casi todo el mundo.
//
// Esto prueba, de extremo a extremo y sin conexion a Microsoft (las llaves RSA se generan en la prueba), las dos
// mitades del asunto: que entren inquilinos distintos, y que NO entre quien se disfraza de Entra. Los emisores
// raros se recorren uno por uno en ValidadorDeEmisorMultiTenantTest; aqui se comprueba que la regla este de
// verdad enchufada en la cadena de filtros y que el resultado que ve el cliente sea 401.
@SpringBootTest(properties = {
        "CLAUDE_API_KEY=",
        "auth.mode=oidc",
        "auth.multi-tenant=true",
        // las dos formas que Entra puede poner en `aud` segun como este configurada la aplicacion
        "auth.oidc.audience=api://growlink-cursos," + TokenOidcMultiTenantTest.CLIENT_ID,
        // sin AUTH_ISSUER_URI: en multi-inquilino no hay un emisor fijo que configurar
        // y sin lista de inquilinos permitidos: se acepta cualquiera de Entra
        "auth.oidc.jwk-set-uri=http://127.0.0.1:1/jwks-que-no-se-consulta"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosoidcmulti;MODE=PostgreSQL")
@Import(TokenOidcMultiTenantTest.DecodificadorConLaLlaveDeLaPrueba.class)
class TokenOidcMultiTenantTest {

    static final String CLIENT_ID = "11112222-3333-4444-5555-666677778888";
    private static final String AUDIENCIA = "api://growlink-cursos";

    private static final String INQUILINO_A = "11111111-2222-3333-4444-555555555555";
    private static final String INQUILINO_B = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    // cuentas personales de Microsoft: el inquilino es siempre este
    private static final String CUENTAS_PERSONALES = "9188040d-6c67-4c5b-b112-36a304b66dad";

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

    // ------------------------------------------------------------------ varios inquilinos, todos bienvenidos

    @Test
    void entraUnUsuarioDelInquilinoA() throws Exception {
        aceptado(Datos.de(INQUILINO_A, AUDIENCIA));
    }

    @Test
    void yTambienUnoDeOtroInquilinoDistinto() throws Exception {
        aceptado(Datos.de(INQUILINO_B, AUDIENCIA));
    }

    @Test
    void yUnaCuentaPersonalDeMicrosoft() throws Exception {
        aceptado(Datos.de(CUENTAS_PERSONALES, AUDIENCIA));
    }

    @Test
    void elUsuarioDelTokenSigueLlegandoALaAplicacion() throws Exception {
        // 404 (no tiene roadmap), NO 401 ni 403: se autentico y se le reconocio como el usuario del claim
        Datos datos = Datos.de(INQUILINO_A, AUDIENCIA);
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", String.valueOf(datos.usuarioId()))
                        .header("Authorization", "Bearer " + datos.firmar()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ la audiencia como lista

    @Test
    void laAudienciaPasaConCualquieraDeLasFormasConfiguradas() throws Exception {
        aceptado(Datos.de(INQUILINO_A, AUDIENCIA).conAudiencias(AUDIENCIA));
        aceptado(Datos.de(INQUILINO_A, AUDIENCIA).conAudiencias(CLIENT_ID));
        // y si el token trae varias, basta con que una sea la nuestra
        aceptado(Datos.de(INQUILINO_A, AUDIENCIA).conAudiencias("api://otra-cosa", CLIENT_ID));
    }

    @Test
    void unaAudienciaQueNoEsLaDeEsteServicioSeRechaza() throws Exception {
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).conAudiencias("api://otra-aplicacion-del-inquilino"));
    }

    // ------------------------------------------------------------------ lo que hay que rechazar

    @Test
    void unTokenCuyoTidNoEsElDelEmisorSeRechaza() throws Exception {
        // emisor del inquilino A, pero el token dice ser del B: sin esta comprobacion, cualquiera podria
        // presentarse como si fuera de otro inquilino
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).conInquilino(INQUILINO_B));
    }

    @Test
    void unEmisorConHostParecidoAlDeMicrosoftSeRechaza() throws Exception {
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA)
                .conEmisor("https://login.microsoftonline.com.evil.com/" + INQUILINO_A + "/v2.0"));
    }

    @Test
    void unEmisorPorHttpSeRechaza() throws Exception {
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA)
                .conEmisor("http://login.microsoftonline.com/" + INQUILINO_A + "/v2.0"));
    }

    @Test
    void unEmisorSinLaVersionV20SeRechaza() throws Exception {
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA)
                .conEmisor("https://login.microsoftonline.com/" + INQUILINO_A));
    }

    @Test
    void unEmisorDeLosPuntosDeEntradaComunesSeRechaza() throws Exception {
        // /common y /organizations son por donde se pide el token, pero NUNCA son el emisor de uno ya emitido:
        // si llega uno asi, no se sabe de que inquilino es
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA)
                .conEmisor("https://login.microsoftonline.com/common/v2.0").conInquilino("common"));
    }

    @Test
    void enMultiInquilinoSiguenRechazandoseElTokenVencidoYElMalFirmado() throws Exception {
        // lo que ya valia antes sigue valiendo: multi-inquilino solo cambia como se mira el emisor
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).vencido());
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).firmadoConOtraLlave());
    }

    @Test
    void unTokenSinFechaDeVencimientoSeRechaza() throws Exception {
        // un token sin `exp` seria valido para siempre, y con el universo de emisores abierto eso pesa mas
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).queNoVence());
    }

    @Test
    void unTokenSinSujetoSeRechaza() throws Exception {
        rechazado(Datos.de(INQUILINO_A, AUDIENCIA).sinSujeto());
    }

    @Test
    void sinTokenSeResponde401() throws Exception {
        mockMvc.perform(get("/api/cursos")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ ayudas

    private void aceptado(Datos datos) throws Exception {
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + datos.firmar()))
                .andExpect(status().isOk());
    }

    private void rechazado(Datos datos) throws Exception {
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + datos.firmar()))
                .andExpect(status().isUnauthorized());
    }
}
