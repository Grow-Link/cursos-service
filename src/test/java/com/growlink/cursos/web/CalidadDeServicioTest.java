package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Atributos de calidad de cursos-service, cada uno con su escenario comprobable (ver infra/docs/atributos-de-calidad.md):
//  - disponibilidad: la salud del servicio se puede consultar sin credenciales y sin filtrar detalles;
//  - observabilidad: cada peticion lleva un X-Request-Id (se respeta uno valido, se reemplaza uno sospechoso);
//  - seguridad y costo: pedir roadmaps sin parar termina en 429 con Retry-After, y el tope es por persona.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursoscalidad;MODE=PostgreSQL",
        "roadmap.limite.generaciones=2",
        "roadmap.limite.ventana-minutos=10"
})
class CalidadDeServicioTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry registro;

    // ------------------------------------------------------------------ disponibilidad

    @Test
    void laSaludSeConsultaSinCredencialesYNoFiltraDetalles() throws Exception {
        MvcResult res = mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        JsonNode cuerpo = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(cuerpo.get("status").asText()).isEqualTo("UP");
        // sin detalles: ni nombres de base de datos ni versiones
        assertThat(cuerpo.has("components")).isFalse();
        assertThat(res.getResponse().getContentAsString()).doesNotContainIgnoringCase("h2").doesNotContainIgnoringCase("postgres");
    }

    @Test
    void lasDosSondasDelBalanceadorResponden() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void loQueNoSeDecidioExponerNoEstaDisponible() throws Exception {
        for (String ruta : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers")) {
            assertThat(mockMvc.perform(get(ruta)).andReturn().getResponse().getStatus())
                    .as(ruta).isNotEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ observabilidad

    @Test
    void cadaRespuestaTrae_unIdentificadorDePeticionNuevo() throws Exception {
        String uno = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("X-Request-Id");
        String dos = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(uno).isNotBlank();
        assertThat(UUID.fromString(uno)).isNotNull();
        assertThat(dos).isNotEqualTo(uno);
    }

    @Test
    void siLlegaUnIdentificadorValidoSeRespetaParaSeguirLaPeticionEntreServicios() throws Exception {
        String recibido = mockMvc.perform(get("/actuator/health").header("X-Request-Id", "abc-123_XYZ.456"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(recibido).isEqualTo("abc-123_XYZ.456");
    }

    @Test
    void unIdentificadorSospechosoSeReemplazaEnVezDeCopiarloAlLogYALaRespuesta() throws Exception {
        for (String malo : List.of("corto", "tiene espacios y <script>", "x".repeat(200), "linea\\nfalsa")) {
            String devuelto = mockMvc.perform(get("/actuator/health").header("X-Request-Id", malo))
                    .andReturn().getResponse().getHeader("X-Request-Id");
            assertThat(devuelto).as(malo).isNotEqualTo(malo);
            assertThat(UUID.fromString(devuelto)).isNotNull();
        }
    }

    @Test
    void hastaLasRespuestasRechazadasLlevanElIdentificador() throws Exception {
        // sin token: la seguridad lo rechaza, y aun asi se puede rastrear
        var res = mockMvc.perform(get("/api/cursos")).andReturn().getResponse();
        assertThat(res.getStatus()).isIn(401, 403);
        assertThat(res.getHeader("X-Request-Id")).isNotBlank();
        assertThat(res.getHeader("X-Instancia")).isNotBlank();
    }

    // ------------------------------------------------------------------ seguridad y costo

    @Test
    void pedirRoadmapsSinParar_terminaEn429ConRetryAfterYElTopeEsPorPersona() throws Exception {
        long abusador = 9301L;
        long otra = 9302L;
        double rechazos0 = registro.counter("growlink.roadmap.limite.rechazos").count();

        generar(abusador, 201);
        generar(abusador, 201);
        MvcResult tercero = generar(abusador, 429);
        // y queda contado, para ver en las metricas cuanto abuso frena el tope
        assertThat(registro.counter("growlink.roadmap.limite.rechazos").count() - rechazos0).isEqualTo(1);

        assertThat(tercero.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(Long.parseLong(tercero.getResponse().getHeader("Retry-After"))).isBetween(1L, 601L);
        JsonNode cuerpo = objectMapper.readTree(tercero.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(cuerpo.get("message").asText()).contains("Espera").contains("vuelve a intentarlo");

        // otra persona no se entera: el tope es de cada una
        generar(otra, 201);
        // y leer el roadmap que ya tiene no gasta cupo
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", String.valueOf(abusador))
                .header("Authorization", token(abusador))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ ayudas

    private MvcResult generar(long usuarioId, int estadoEsperado) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", "Quiero aprender Python para analizar datos");
        cuerpo.put("intereses", List.of("INGENIERIA_SISTEMAS"));
        cuerpo.put("nivel", "PRINCIPIANTE");
        return mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado)).andReturn();
    }

    private static String token(long usuarioId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId)).claim("rol", "USUARIO").signWith(key).compact();
    }
}
