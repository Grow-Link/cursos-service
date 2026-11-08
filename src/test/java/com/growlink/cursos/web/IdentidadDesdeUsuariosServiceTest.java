package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.web.LlavesYTokensDePrueba.Datos;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO seguridad: de donde sale la identidad de GrowLink en modo oidc. Con tokens REALES de Entra el `sub` es
// un texto opaco (no el usuarioId numerico de nuestras tablas) y el rol no viene en el token, asi que leerlos de
// los claims rechazaria a todo el mundo. La identidad la resuelve usuarios-service, que es su dueno.
//
// usuarios-service se simula con un servidor HTTP local de la propia JVM (com.sun.net.httpserver, que trae el
// JDK): no hace falta ninguna libreria nueva y, al ser sockets de verdad, se prueba el cliente completo
// (incluidos sus tiempos limite, que con un servidor simulado en memoria no se ejercitarian). Las llaves RSA se
// generan en la prueba, asi que tampoco hay conexion a Microsoft.
//
// La vigencia de la cache se baja a 2 s para poder ver de extremo a extremo que vence; las reglas finas de la
// cache (el tope del `exp` del token, el tope de entradas) estan en CacheDeIdentidadesTest con un reloj de
// mentiras.
@SpringBootTest(properties = {
        "CLAUDE_API_KEY=",
        "auth.mode=oidc",
        "auth.multi-tenant=true",
        "auth.oidc.audience=api://growlink-cursos",
        "auth.oidc.jwk-set-uri=http://127.0.0.1:1/jwks-que-no-se-consulta",
        "auth.identity-source=usuarios-service",
        "auth.identity-cache-seconds=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosidentidad;MODE=PostgreSQL")
@Import(IdentidadDesdeUsuariosServiceTest.DecodificadorConLaLlaveDeLaPrueba.class)
class IdentidadDesdeUsuariosServiceTest {

    private static final String INQUILINO = "11111111-2222-3333-4444-555555555555";
    private static final String AUDIENCIA = "api://growlink-cursos";

    // ------------------------------------------------------------------ el usuarios-service de mentiras

    private interface Manejador {
        void atender(HttpExchange intercambio) throws IOException;
    }

    private static final HttpServer SERVIDOR = arrancarServidor();
    private static final AtomicInteger LLAMADAS = new AtomicInteger();
    private static volatile Manejador manejador = intercambio -> responder(intercambio, 200, me(1L, "USUARIO"));
    private static volatile String ultimaAutorizacion;
    private static volatile String ultimoIdDePeticion;

    private static HttpServer arrancarServidor() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/usuarios/me", intercambio -> {
                LLAMADAS.incrementAndGet();
                ultimaAutorizacion = intercambio.getRequestHeaders().getFirst("Authorization");
                ultimoIdDePeticion = intercambio.getRequestHeaders().getFirst("X-Request-Id");
                manejador.atender(intercambio);
            });
            // un pool pequeno pero con mas de un hilo: el de la prueba del tiempo limite se queda dormido
            servidor.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo levantar el usuarios-service de prueba", e);
        }
    }

    @DynamicPropertySource
    static void apuntarAlServidorDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.usuarios-service.url",
                () -> "http://127.0.0.1:" + SERVIDOR.getAddress().getPort());
    }

    private static String me(long usuarioId, String rol) {
        // la forma real de MeResponse en usuarios-service: trae mas campos de los que este servicio usa
        return "{\"usuarioId\":" + usuarioId + ",\"rol\":\"" + rol + "\",\"rolElegido\":true,"
                + "\"correo\":\"alguien@example.com\",\"nombre\":\"Alguien\"}";
    }

    private static void responder(HttpExchange intercambio, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json");
        intercambio.sendResponseHeaders(estado, bytes.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
    }

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
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void desdeCero() {
        LLAMADAS.set(0);
        ultimaAutorizacion = null;
        ultimoIdDePeticion = null;
        manejador = intercambio -> responder(intercambio, 200, me(1L, "USUARIO"));
    }

    // Cada prueba usa un `sub` distinto para que su token tenga una huella distinta: la cache de identidades es
    // un singleton que vive mientras viva el contexto, y asi una prueba no se encuentra lo que guardo otra.
    private static Datos tokenDeEntra(String sujeto) {
        return Datos.de(INQUILINO, AUDIENCIA).conSujeto(sujeto).sinIdNumerico();
    }

    // ------------------------------------------------------------------ la identidad sale de usuarios-service

    @Test
    void unTokenConSubOpacoDeEntraSeResuelveAlUsuarioQueDiceUsuariosService() throws Exception {
        manejador = intercambio -> responder(intercambio, 200, me(42L, "USUARIO"));
        String token = tokenDeEntra("AAAAAAAAAAAAAAAAAAAAAIk_bMBSKsTxQ4Y-eV3VvQ4").firmar();

        // el token NO trae ningun numero: si la identidad se leyera de los claims, esto seria un 401
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", "42")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()); // 404 = autenticado y reconocido como el usuario 42

        // y no es "cualquier usuario": pedir el roadmap de otro le da 403
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", "99")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void seLeReenviaAUsuariosServiceElMismoTokenYElIdDeLaPeticion() throws Exception {
        String token = tokenDeEntra("sub-del-reenvio").firmar();

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "abc-123_XYZ.456"))
                .andExpect(status().isOk());

        // el token va tal cual: usuarios-service lo valida por su cuenta, aqui no se firma nada nuevo
        assertThat(ultimaAutorizacion).isEqualTo("Bearer " + token);
        // y el identificador de la peticion viaja con ella, para poder seguirla de un servicio al otro
        assertThat(ultimoIdDePeticion).isEqualTo("abc-123_XYZ.456");
    }

    // ------------------------------------------------------------------ la cache

    @Test
    void dosPeticionesConElMismoTokenCuestanUnaSolaConsulta() throws Exception {
        String token = tokenDeEntra("sub-de-la-cache").firmar();

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        assertThat(LLAMADAS.get()).as("la segunda sale de la cache").isEqualTo(1);
    }

    @Test
    void tokensDistintosSeConsultanPorSeparado() throws Exception {
        mockMvc.perform(get("/api/cursos")
                .header("Authorization", "Bearer " + tokenDeEntra("sub-uno").firmar())).andExpect(status().isOk());
        mockMvc.perform(get("/api/cursos")
                .header("Authorization", "Bearer " + tokenDeEntra("sub-dos").firmar())).andExpect(status().isOk());

        assertThat(LLAMADAS.get()).isEqualTo(2);
    }

    @Test
    void alVencerLaCacheSeVuelveAConsultar() throws Exception {
        String token = tokenDeEntra("sub-que-vence").firmar();

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(LLAMADAS.get()).isEqualTo(1);

        Thread.sleep(2300); // la vigencia de esta prueba es de 2 s

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(LLAMADAS.get()).as("ya no vale la guardada: se vuelve a preguntar").isEqualTo(2);
    }

    @Test
    void conLaIdentidadYaGuardadaSeSigueTrabajandoAunqueUsuariosServiceSeCaiga() throws Exception {
        String token = tokenDeEntra("sub-del-aguante").firmar();
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // usuarios-service se cae DESPUES de haber contestado una vez
        manejador = intercambio -> responder(intercambio, 500, "{\"message\":\"se cayo\"}");

        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(LLAMADAS.get()).as("no se le volvio a preguntar: estaba en la cache").isEqualTo(1);
    }

    // ------------------------------------------------------------------ cuando usuarios-service dice que no

    @Test
    void siUsuariosServiceNoReconoceElTokenSeResponde401() throws Exception {
        manejador = intercambio -> responder(intercambio, 401, "{\"message\":\"token no valido\"}");

        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra("sub-del-401").firmar()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void siElTokenNoCorrespondeANingunUsuarioDeGrowLinkSeResponde401() throws Exception {
        // 404: se autentico con Microsoft, pero aqui no existe. Reintentar no lo arregla, asi que no es un 503
        manejador = intercambio -> responder(intercambio, 404, "{\"message\":\"no existe\"}");

        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra("sub-del-404").firmar()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ cuando usuarios-service no contesta

    @Test
    void siUsuariosServiceFallaConUn5xxSeResponde503YNuncaSeAceptaElToken() throws Exception {
        manejador = intercambio -> responder(intercambio, 500, "{\"message\":\"se cayo\"}");

        var respuesta = mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra("sub-del-500").firmar()))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse();

        // 503 y no 401: el token puede estar perfecto, el problema es nuestro
        assertThat(respuesta.getHeader("Retry-After")).isNotBlank();
        assertThat(objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8))
                .get("message").asText()).contains("Vuelve a intentarlo");
    }

    @Test
    void siUsuariosServiceSeDemoraMasDelTiempoLimiteSeResponde503() throws Exception {
        manejador = intercambio -> {
            try {
                Thread.sleep(5000); // el tiempo limite de lectura del cliente es de 3 s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responder(intercambio, 200, me(1L, "USUARIO"));
        };

        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra("sub-de-la-demora").firmar()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void siNoHayNadieEscuchandoSeResponde503() throws Exception {
        // no se puede apagar el servidor compartido, asi que se prueba la pieza con una URL donde no hay nadie
        var cliente = new com.growlink.cursos.infrastructure.security.ClienteDeUsuariosService(
                "http://127.0.0.1:1", java.time.Duration.ofSeconds(60), 10);
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
                .header("alg", "RS256").subject("s").claim("iss", "x").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cliente.identidadDe(token))
                .isInstanceOf(com.growlink.cursos.infrastructure.security.IdentidadNoDisponibleException.class);
    }

    @Test
    void unaRespuestaSinUsuarioIdSeTrataComoFalla() throws Exception {
        // 200 pero sin lo unico que se le pidio: es un problema del contrato, no del token
        manejador = intercambio -> responder(intercambio, 200, "{\"rol\":\"USUARIO\"}");

        mockMvc.perform(get("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra("sub-sin-id").firmar()))
                .andExpect(status().isServiceUnavailable());
    }

    // ------------------------------------------------------------------ el rol tambien sale de ahi

    @Test
    void elRolQueDiceUsuariosServiceEsElQueManda() throws Exception {
        // USUARIO no puede publicar...
        manejador = intercambio -> responder(intercambio, 200, me(5001L, "USUARIO"));
        crearCurso("Curso de un usuario comun", 5001L, "sub-usuario", 403);

        // ...PUBLICADOR si...
        manejador = intercambio -> responder(intercambio, 200, me(5002L, "PUBLICADOR"));
        crearCurso("Curso de un publicador", 5002L, "sub-publicador", 201);

        // ...y ADMIN tambien
        manejador = intercambio -> responder(intercambio, 200, me(5003L, "ADMIN"));
        crearCurso("Curso de un admin", 5003L, "sub-admin", 201);
    }

    @Test
    void siUsuariosServiceNoLeDaRolTodaviaQuedaAutenticadoPeroSinPermisos() throws Exception {
        // en usuarios-service el rol se elige despues de entrar; mientras no lo tenga, no puede publicar
        manejador = intercambio -> responder(intercambio,
                200, "{\"usuarioId\":5004,\"rol\":null,\"rolElegido\":false}");
        String token = tokenDeEntra("sub-sin-rol").firmar();

        // autenticado: puede leer el catalogo
        mockMvc.perform(get("/api/cursos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // pero sin permisos: no puede publicar
        crearCurso("Curso sin rol", 5004L, "sub-sin-rol", 403);
    }

    private void crearCurso(String titulo, long publicadorId, String sujeto, int estadoEsperado) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorId);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", 20);

        mockMvc.perform(post("/api/cursos")
                        .header("Authorization", "Bearer " + tokenDeEntra(sujeto).firmar())
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado));
    }
}
