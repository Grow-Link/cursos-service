package com.growlink.cursos.application;

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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ANTI-FARMING parte 1, probado contra el algoritmo de RESPALDO REAL (sin IA falsa ni CLAUDE_API_KEY, igual que
// CursoFeaturesTest/CatalogoYRoadmapRealTest): un roadmap nuevo no puede volver a ofrecer un curso que la persona
// ya completo, un prerequisito ya completado no bloquea al curso que lo necesitaba, y si ya completo todo lo
// que hay en las areas elegidas se explica con un mensaje propio (no el de "no hay cursos", que es otro caso).
// Base propia (categoria DERECHO, que el catalogo de demostracion no usa) para controlar exactamente que
// cursos existen y poder hacer que la meta elegida solo coincida con el curso que a cada prueba le interesa.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:antifarminggeneracion;MODE=PostgreSQL")
class RoadmapAntiFarmingGeneracionTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generarExcluyeCursosYaCompletadosYNoBloqueaAlQueLosNecesitaba() throws Exception {
        long usuarioId = 7501L;
        // c1 no comparte ninguna palabra con la meta de abajo: si aparece en la ruta, es SOLO porque el cierre
        // de prerequisitos lo arrastro (lo que pasaria si no estuviera ya completado)
        Long c1 = crearCurso("Derecho Procesal Introductorio", usuarioId, List.of());
        Long c2 = crearCurso("Derecho de Contratos Civiles", usuarioId, List.of(c1));
        completar(c1, usuarioId);

        JsonNode roadmap = generarOk(usuarioId, "Quiero entender bien los contratos civiles");
        List<Long> ids = idsDelRoadmap(roadmap);
        assertThat(ids).contains(c2).doesNotContain(c1);
    }

    @Test
    void siYaCompletoTodosLosCursosDeLAreaElegidaResponde422ConMensajePropio() throws Exception {
        long usuarioId = 7502L;
        Long unico = crearCurso("Derecho Laboral Individual", usuarioId, List.of());
        completar(unico, usuarioId);

        String cuerpo = generar(usuarioId, "Quiero dominar el derecho laboral individual", 422);
        assertThat(cuerpo).contains("Ya completaste");

        // un area que de verdad no tiene NINGUN curso (ni siquiera completado) sigue dando el mensaje de antes,
        // para no confundir "no hay nada" con "ya te lo sabes todo"
        String otroCuerpo = generarConArea(9999L, "Quiero aprender ingenieria electronica basica",
                "INGENIERIA_ELECTRONICA", 422);
        assertThat(otroCuerpo).contains("Todavía no hay cursos de").doesNotContain("Ya completaste");
    }

    // ------------------------------------------------------------------ ayudas

    private Long crearCurso(String titulo, long publicadorId, List<Long> prerequisitoIds) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorId);
        cuerpo.put("prerequisitoIds", prerequisitoIds);
        cuerpo.put("duracionHoras", 10);

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

    private JsonNode generarOk(long usuarioId, String metas) throws Exception {
        return objectMapper.readTree(generar(usuarioId, metas, 201));
    }

    private String generar(long usuarioId, String metas, int estadoEsperado) throws Exception {
        return generarConArea(usuarioId, metas, "DERECHO", estadoEsperado);
    }

    private String generarConArea(long usuarioId, String metas, String area, int estadoEsperado) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", metas);
        cuerpo.put("intereses", List.of(area));
        cuerpo.put("nivel", "PRINCIPIANTE");

        return mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Long> idsDelRoadmap(JsonNode roadmap) {
        return StreamSupport.stream(roadmap.get("cursos").spliterator(), false)
                .map(c -> c.get("cursoId").asLong()).toList();
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
