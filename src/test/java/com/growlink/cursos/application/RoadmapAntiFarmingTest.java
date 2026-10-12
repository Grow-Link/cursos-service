package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.RoadmapCursoRepository;
import com.growlink.cursos.adapter.persistence.RoadmapRepository;
import com.growlink.cursos.domain.FuenteRoadmap;
import com.growlink.cursos.domain.Nivel;
import com.growlink.cursos.domain.Roadmap;
import com.growlink.cursos.domain.RoadmapCurso;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ANTI-FARMING parte 1 (la IA propone un curso ya completado: se rechaza y cae al respaldo) y parte 2 completa
// (reglas de GET /api/roadmap/completados). Usa la misma IA falsa que RoadmapRestriccionTest/
// RoadmapCompletadosTest para controlar exactamente que cursos entran a cada roadmap. Base propia (DERECHO,
// que el catalogo de demostracion no usa) para que los conteos no se mezclen con otras pruebas.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:roadmapantifarming;MODE=PostgreSQL")
@Import(RoadmapAntiFarmingTest.IaFalsaConfig.class)
class RoadmapAntiFarmingTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    static class IaFalsa extends ClaudeRoadmapAiClient {
        volatile Function<List<CursoGrafoNodo>, List<Long>> decide = catalogo -> List.of();

        IaFalsa() {
            super("llave-falsa", "modelo-falso", 0.0, new ObjectMapper());
        }

        @Override
        public PropuestaRoadmap generarRuta(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
            List<Long> ids = decide.apply(catalogoActivo);
            List<CursoElegido> cursos = ids.stream().map(id -> new CursoElegido(id, "razon de prueba")).toList();
            return new PropuestaRoadmap(cursos, "resumen de prueba");
        }
    }

    @TestConfiguration
    static class IaFalsaConfig {
        @Bean
        @Primary
        IaFalsa iaFalsa() {
            return new IaFalsa();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private IaFalsa ia;
    @Autowired
    private RoadmapRepository roadmapRepository;
    @Autowired
    private RoadmapCursoRepository roadmapCursoRepository;

    @BeforeEach
    void iaPorDefecto() {
        ia.decide = catalogo -> List.of();
    }

    // ------------------------------------------------------------------ parte 1: validacion de la propuesta de la IA

    @Test
    void propuestaDeIaConCursoCompletadoCaeAlRespaldo() throws Exception {
        long usuarioId = 7601L;
        Long completado = crearCurso("Derecho Comercial Primero", usuarioId, 10);
        Long disponible = crearCurso("Derecho Comercial Segundo", usuarioId, 10);
        completar(completado, usuarioId);

        // la IA (falsa) insiste en proponer un curso que esta persona ya completo: eso ya no esta en el
        // catalogo disponible que se valida, asi que se rechaza y se usa el respaldo real
        ia.decide = catalogo -> List.of(completado);
        JsonNode roadmap = generarOk(usuarioId, "Quiero trabajar en derecho comercial");

        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(idsDelRoadmap(roadmap)).doesNotContain(completado).contains(disponible);
    }

    // ------------------------------------------------------------------ parte 2: regla anti-farming de /completados

    @Test
    void roadmapConCursoCompletadoAntesDeCrearseNoApareceEnCompletados() throws Exception {
        long usuarioId = 7602L;
        Long cursoId = crearCurso("Derecho Agrario Historico", usuarioId, 10);
        completar(cursoId, usuarioId); // completado PRIMERO

        // roadmap "de antes de la parte 1" (cuando un roadmap nuevo si podia incluir un curso ya completado):
        // se simula insertandolo directo, con un curso que para esta persona ya estaba completado antes de
        // que este roadmap existiera
        Roadmap legado = roadmapRepository.save(new Roadmap(usuarioId, "Meta vieja de antes del arreglo",
                Nivel.PRINCIPIANTE, FuenteRoadmap.RESPALDO, null, "DERECHO", null, null));
        roadmapCursoRepository.save(new RoadmapCurso(legado.getId(), cursoId, 0, "razon vieja"));

        assertThat(completados(usuarioId, 200)).isEmpty();
    }

    @Test
    void dosRoadmapsConLosMismosCursosNuevosCompletadosJuntosSoloUnoCuenta() throws Exception {
        long usuarioId = 7603L;
        Long c1 = crearCurso("Derecho Minero Uno", usuarioId, 10);
        Long c2 = crearCurso("Derecho Minero Dos", usuarioId, 10);

        Long roadmapA = generarCon(usuarioId, List.of(c1, c2), "Quiero aprender derecho minero (ronda A)");
        Long roadmapB = generarCon(usuarioId, List.of(c1, c2), "Quiero aprender derecho minero (ronda B)");
        assertThat(roadmapA).isNotEqualTo(roadmapB);

        // ambos cursos se completan juntos: alimentan a los DOS roadmaps por igual, pero cada curso solo paga
        // una semilla, asi que solo uno de los dos roadmaps puede quedar completado
        completar(c1, usuarioId);
        completar(c2, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        // determinista: como ambos quedan completados en el mismo instante, gana el que se creo primero (A)
        assertThat(lista.get(0).get("roadmapId").asLong()).isEqualTo(roadmapA);
    }

    @Test
    void roadmapNormalCompletadoDespuesDeCrearseApareceConElFormatoEsperado() throws Exception {
        long usuarioId = 7604L;
        Long c1 = crearCurso("Derecho Notarial Uno", usuarioId, 20);
        Long c2 = crearCurso("Derecho Notarial Dos", usuarioId, 15);
        Long roadmapId = generarCon(usuarioId, List.of(c1, c2), "Quiero trabajar en derecho notarial");
        completar(c1, usuarioId);
        completar(c2, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        JsonNode entrada = lista.get(0);
        assertThat(entrada.get("roadmapId").asLong()).isEqualTo(roadmapId);
        assertThat(entrada.get("categoria").asText()).isEqualTo("DERECHO");
        assertThat(entrada.get("titulo").asText()).isEqualTo("Quiero trabajar en derecho notarial");
        assertThat(entrada.get("totalCursos").asInt()).isEqualTo(2);
        assertThat(entrada.get("horasTotales").asInt()).isEqualTo(35);
        assertThat(entrada.get("completadoEn").asText()).isNotBlank();
    }

    @Test
    void unUsuarioNoVeRoadmapsDeOtro() throws Exception {
        long usuarioX = 7605L;
        long usuarioY = 7606L;
        Long cursoDeX = crearCurso("Derecho Aduanero de X", usuarioX, 10);
        Long cursoDeY = crearCurso("Derecho Aduanero de Y", usuarioY, 10);
        Long roadmapX = generarCon(usuarioX, List.of(cursoDeX), "Quiero trabajar en derecho aduanero (X)");
        Long roadmapY = generarCon(usuarioY, List.of(cursoDeY), "Quiero trabajar en derecho aduanero (Y)");
        completar(cursoDeX, usuarioX);
        completar(cursoDeY, usuarioY);

        JsonNode listaX = completados(usuarioX, 200);
        assertThat(listaX).hasSize(1);
        assertThat(listaX.get(0).get("roadmapId").asLong()).isEqualTo(roadmapX);

        JsonNode listaY = completados(usuarioY, 200);
        assertThat(listaY).hasSize(1);
        assertThat(listaY.get(0).get("roadmapId").asLong()).isEqualTo(roadmapY);

        // y por supuesto, pedir el de otro sin ser ADMIN se rechaza
        mockMvc.perform(get("/api/roadmap/completados").param("usuarioId", String.valueOf(usuarioY))
                        .header("Authorization", token(usuarioX, "STUDENT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ayudas

    private JsonNode completados(long usuarioId, int estadoEsperado) throws Exception {
        String respuesta = mockMvc.perform(get("/api/roadmap/completados").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta);
    }

    private Long generarCon(long usuarioId, List<Long> cursoIds, String metas) throws Exception {
        ia.decide = catalogo -> cursoIds;
        return generarOk(usuarioId, metas).get("id").asLong();
    }

    private JsonNode generarOk(long usuarioId, String metas) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", metas);
        cuerpo.put("intereses", List.of("DERECHO"));
        cuerpo.put("nivel", "PRINCIPIANTE");

        String respuesta = mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta);
    }

    private List<Long> idsDelRoadmap(JsonNode roadmap) {
        List<Long> ids = new ArrayList<>();
        roadmap.get("cursos").forEach(c -> ids.add(c.get("cursoId").asLong()));
        return ids;
    }

    private Long crearCurso(String titulo, long publicadorId, int duracionHoras) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorId);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", duracionHoras);

        String respuesta = mockMvc.perform(post("/api/cursos").header("Authorization", token(publicadorId, "PROFESSOR"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private void completar(Long cursoId, long usuarioId) throws Exception {
        mockMvc.perform(post("/api/cursos/" + cursoId + "/completar").header("Authorization", token(3L, "ADMIN"))
                        .contentType("application/json").content("{\"usuarioId\": " + usuarioId + "}"))
                .andExpect(status().isCreated());
    }

    private static String token(long usuarioId, String rol) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(String.valueOf(usuarioId))
                .claim("roles", List.of(rol))
                .signWith(key)
                .compact();
    }
}
