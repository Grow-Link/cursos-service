package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.RoadmapRepository;
import com.growlink.cursos.domain.FuenteRoadmap;
import com.growlink.cursos.domain.Nivel;
import com.growlink.cursos.domain.Roadmap;
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

// GET /api/roadmap/completados: CONTRATO con usuarios-service, da una semilla de jardin por cada roadmap
// completado. Usa la misma IA falsa que RoadmapRestriccionTest para controlar exactamente que cursos (y en
// que orden) entran a cada roadmap, sin depender de que la meta "le caiga en gracia" al modo de respaldo.
// Base propia, para que los cursos/roadmaps de otras pruebas no se mezclen con los conteos de esta.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:roadmapcompletados;MODE=PostgreSQL")
@Import(RoadmapCompletadosTest.IaFalsaConfig.class)
class RoadmapCompletadosTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    // misma idea que en RoadmapRestriccionTest: reemplaza al cliente real de Claude, nunca sale a internet
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

    @BeforeEach
    void iaPorDefecto() {
        ia.decide = catalogo -> List.of();
    }

    @Test
    void roadmapCompletoApareceConSusDatos() throws Exception {
        long usuarioId = 8001L;
        Long c1 = crearCurso("Derecho Civil Uno", "DERECHO", usuarioId, 20);
        Long c2 = crearCurso("Derecho Civil Dos", "DERECHO", usuarioId, 15);
        Long roadmapId = generarCon(usuarioId, List.of(c1, c2), "Quiero trabajar en derecho civil");
        completar(c1, usuarioId);
        completar(c2, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        JsonNode entrada = lista.get(0);
        assertThat(entrada.get("roadmapId").asLong()).isEqualTo(roadmapId);
        assertThat(entrada.get("categoria").asText()).isEqualTo("DERECHO");
        assertThat(entrada.get("titulo").asText()).isEqualTo("Quiero trabajar en derecho civil");
        assertThat(entrada.get("totalCursos").asInt()).isEqualTo(2);
        assertThat(entrada.get("horasTotales").asInt()).isEqualTo(35);
        assertThat(entrada.get("completadoEn").asText()).isNotBlank();
    }

    @Test
    void roadmapIncompletoNoAparece() throws Exception {
        long usuarioId = 8002L;
        Long c1 = crearCurso("Derecho Penal Uno", "DERECHO", usuarioId, 10);
        Long c2 = crearCurso("Derecho Penal Dos", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(c1, c2), "Quiero trabajar en derecho penal");
        completar(c1, usuarioId); // falta c2

        assertThat(completados(usuarioId, 200)).isEmpty();
    }

    @Test
    void roadmapSinCursosNoAparece() throws Exception {
        long usuarioId = 8003L;
        // esto nunca pasa por la API (un roadmap siempre tiene al menos un curso), pero se prueba el caso
        // defensivo insertando el roadmap directo por el repositorio, sin ningun RoadmapCurso
        roadmapRepository.save(new Roadmap(usuarioId, "Quiero trabajar en algo cualquiera", Nivel.PRINCIPIANTE,
                FuenteRoadmap.RESPALDO, null, "DERECHO", null, null));

        assertThat(completados(usuarioId, 200)).isEmpty();
    }

    @Test
    void deOtraPersonaResponde403() throws Exception {
        long usuarioId = 8004L;
        long otraPersona = 8005L;
        Long c1 = crearCurso("Derecho Laboral Uno", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(c1), "Quiero trabajar en derecho laboral");
        completar(c1, usuarioId);

        mockMvc.perform(get("/api/roadmap/completados").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(otraPersona, "STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cursoDadoDeBajaPeroCompletadoSigueContando() throws Exception {
        long usuarioId = 8006L;
        long publicadorId = 8007L;
        Long c1 = crearCurso("Derecho Comercial Uno", "DERECHO", publicadorId, 25);
        generarCon(usuarioId, List.of(c1), "Quiero trabajar en derecho comercial");
        completar(c1, usuarioId);

        mockMvc.perform(patch("/api/cursos/" + c1 + "/baja").header("Authorization", token(publicadorId, "PROFESSOR")))
                .andExpect(status().isNoContent());

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("categoria").asText()).isEqualTo("DERECHO");
        assertThat(lista.get(0).get("horasTotales").asInt()).isEqualTo(25);
    }

    @Test
    void categoriaEsLaDeMasCursos() throws Exception {
        long usuarioId = 8008L;
        // orden: DERECHO, MATEMATICAS, DERECHO -> DERECHO tiene 2, MATEMATICAS 1: gana DERECHO
        Long c1 = crearCurso("Derecho Mayoria Uno", "DERECHO", usuarioId, 10);
        Long c2 = crearCurso("Matematicas Minoria", "MATEMATICAS", usuarioId, 10);
        Long c3 = crearCurso("Derecho Mayoria Dos", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(c1, c2, c3), "Quiero trabajar en derecho y matematicas");
        completar(c1, usuarioId);
        completar(c2, usuarioId);
        completar(c3, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("categoria").asText()).isEqualTo("DERECHO");
    }

    @Test
    void enEmpateDeCategoriaGanaLaDelCursoQueApareceDePrimeroPorOrden() throws Exception {
        long usuarioId = 8009L;
        // orden: MATEMATICAS, DERECHO, MATEMATICAS, DERECHO -> empatan 2 y 2, pero MATEMATICAS aparece primero
        Long c1 = crearCurso("Matematicas Empate Uno", "MATEMATICAS", usuarioId, 10);
        Long c2 = crearCurso("Derecho Empate Uno", "DERECHO", usuarioId, 10);
        Long c3 = crearCurso("Matematicas Empate Dos", "MATEMATICAS", usuarioId, 10);
        Long c4 = crearCurso("Derecho Empate Dos", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(c1, c2, c3, c4), "Quiero trabajar en matematicas y derecho juntos");
        completar(c1, usuarioId);
        completar(c2, usuarioId);
        completar(c3, usuarioId);
        completar(c4, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("categoria").asText()).isEqualTo("MATEMATICAS");
    }

    @Test
    void variosRoadmapsSoloCuentanLosQueEstanCompletosDelTodo() throws Exception {
        long usuarioId = 8010L;
        Long a1 = crearCurso("Roadmap A Curso Uno", "DERECHO", usuarioId, 10);
        Long a2 = crearCurso("Roadmap A Curso Dos", "DERECHO", usuarioId, 10);
        Long roadmapA = generarCon(usuarioId, List.of(a1, a2), "Quiero completar el roadmap A de derecho");
        completar(a1, usuarioId);
        completar(a2, usuarioId);

        // un segundo roadmap del mismo usuario, generado despues, que se deja incompleto: no borra ni oculta
        // que el primero (A) ya quedo completo, aunque "mio" ahora muestre este segundo
        Long b1 = crearCurso("Roadmap B Curso Uno", "DERECHO", usuarioId, 10);
        Long b2 = crearCurso("Roadmap B Curso Dos", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(b1, b2), "Quiero completar el roadmap B de derecho");
        completar(b1, usuarioId); // falta b2

        JsonNode lista = completados(usuarioId, 200);
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("roadmapId").asLong()).isEqualTo(roadmapA);

        // y "mio" sigue viendo el ultimo generado (B), no el completado (A): generar uno nuevo no borra el anterior
        String mio = mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(mio).get("id").asLong()).isNotEqualTo(roadmapA);
    }

    @Test
    void elTituloSeRecortaA80CaracteresSinCortarUnaPalabra() throws Exception {
        long usuarioId = 8011L;
        String meta = "Quiero prepararme profesionalmente en el area completa de derecho constitucional "
                + "y administrativo para ejercer con excelencia";
        assertThat(meta.length()).isGreaterThan(80);
        Long c1 = crearCurso("Derecho Largo Uno", "DERECHO", usuarioId, 10);
        generarCon(usuarioId, List.of(c1), meta);
        completar(c1, usuarioId);

        JsonNode lista = completados(usuarioId, 200);
        String titulo = lista.get(0).get("titulo").asText();
        assertThat(titulo).endsWith("…");
        assertThat(titulo.length()).isLessThanOrEqualTo(81);
        String sinElipsis = titulo.substring(0, titulo.length() - 1);
        assertThat(meta).startsWith(sinElipsis);
        // el caracter justo despues del recorte es un espacio (o el fin del texto): no se partio una palabra
        assertThat(meta.charAt(sinElipsis.length())).isEqualTo(' ');
    }

    // ------------------------------------------------------------------ ayudas

    private JsonNode completados(long usuarioId, int estadoEsperado) throws Exception {
        String respuesta = mockMvc.perform(get("/api/roadmap/completados").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta);
    }

    // genera un roadmap para usuarioId con EXACTAMENTE esos cursoIds, en ese orden (la IA falsa los devuelve
    // tal cual); devuelve el id del roadmap guardado
    private Long generarCon(long usuarioId, List<Long> cursoIds, String metas) throws Exception {
        ia.decide = catalogo -> cursoIds;
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", metas);
        cuerpo.put("intereses", List.of("DERECHO")); // el plan gratuito exige una sola area; no restringe que cursos elige la IA
        cuerpo.put("nivel", "PRINCIPIANTE");

        String respuesta = mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private Long crearCurso(String titulo, String categoria, long publicadorId, int duracionHoras) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", categoria);
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

    // ADMIN completa el curso sin examen, para no tener que armar un examen solo para dejar fixtures listos
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
