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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El catalogo de demostracion sembrado (32 cursos reales con horas, temario, enlace y examen), lo que se le
// muestra a la persona antes de pedirle su meta, el control de metas que no tienen sentido, el respaldo que ahora
// SI mira la meta, el examen que no regala las respuestas, y el usuario de demostracion que ya lleva avance.
// Base propia para que los conteos sean los del catalogo sembrado y no se mezclen con los de otras pruebas.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursoscatalogoreal;MODE=PostgreSQL")
class CatalogoYRoadmapRealTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------- areas y catalogo

    @Test
    void lasAreasSalenDeLosCursosActivosYNoDeUnaListaEscritaAMano() throws Exception {
        JsonNode areas = leer("/api/cursos/resumen", 9100L);

        Map<String, JsonNode> porArea = new HashMap<>();
        areas.forEach(a -> porArea.put(a.get("area").asText(), a));
        assertThat(porArea).containsKeys("INGENIERIA_SISTEMAS", "MATEMATICAS", "ADMINISTRACION_EMPRESAS", "IDIOMAS",
                "INGENIERIA_INDUSTRIAL");
        // las areas que no tienen cursos no aparecen
        assertThat(porArea).doesNotContainKeys("DERECHO", "INGENIERIA_CIVIL");

        JsonNode sistemas = porArea.get("INGENIERIA_SISTEMAS");
        assertThat(sistemas.get("etiqueta").asText()).isEqualTo("Ingeniería de Sistemas");
        assertThat(sistemas.get("cursos").asInt()).isEqualTo(14);
        assertThat(sistemas.get("principiante").asInt() + sistemas.get("intermedio").asInt()
                + sistemas.get("avanzado").asInt()).isEqualTo(14);
        assertThat(sistemas.get("horasTotales").asInt()).isGreaterThan(300);
        assertThat(sistemas.get("habilidades")).isNotEmpty();
        assertThat(sistemas.get("ejemplos")).hasSize(3);
    }

    @Test
    void cadaCursoDelCatalogoTraeHorasTemarioEnlaceYExamen() throws Exception {
        JsonNode cursos = leer("/api/cursos", 9101L);
        assertThat(cursos.size()).isGreaterThanOrEqualTo(32);
        for (JsonNode c : cursos) {
            String titulo = c.get("titulo").asText();
            if (titulo.startsWith("Curso ")) {
                continue; // los que crean otras pruebas de esta misma clase, no son del catalogo sembrado
            }
            assertThat(c.get("duracionHoras").asInt()).as("horas de " + titulo).isGreaterThan(0);
            assertThat(c.get("temario").size()).as("temario de " + titulo).isGreaterThanOrEqualTo(4);
            assertThat(c.get("linkContenido").asText()).as("enlace de " + titulo).startsWith("https://");
            assertThat(c.get("totalPreguntasExamen").asInt()).as("examen de " + titulo).isEqualTo(3);
            assertThat(c.get("descripcion").asText().length()).as("descripcion de " + titulo).isGreaterThan(40);
        }
    }

    // ------------------------------------------------------------- control de metas

    @Test
    void unaMetaSinSentidoSeRechazaConUnMensajeClaro() throws Exception {
        for (String basura : List.of("asdfgh jkl qwerty zzz", "aaa bbb ccc ddd eee", "12345 67890 11111", "hola")) {
            String cuerpo = generar(9102L, basura, List.of("INGENIERIA_SISTEMAS"), "PRINCIPIANTE", 400);
            assertThat(cuerpo).as("meta: " + basura).contains("message");
        }
    }

    @Test
    void unaMetaAjenaALosCursosNoRecibeUnaRutaInventada() throws Exception {
        String cuerpo = generar(9103L, "Quiero cocinar pasteles de chocolate", List.of("INGENIERIA_SISTEMAS"),
                "PRINCIPIANTE", 422);
        assertThat(cuerpo).contains("No encontramos cursos relacionados");
    }

    @Test
    void unAreaSinCursosSeRechazaYNoSeFingeUnaRuta() throws Exception {
        String cuerpo = generar(9104L, "Quiero aprender derecho laboral", List.of("DERECHO"), "PRINCIPIANTE", 422);
        assertThat(cuerpo).contains("Derecho");
        generar(9104L, "Quiero aprender programacion en Python", List.of(), "PRINCIPIANTE", 400);
    }

    // ------------------------------------------------------------- el respaldo mira la meta

    @Test
    void metasDistintasDanRoadmapsDistintosYCadaCursoExplicaPorQueEstaAhi() throws Exception {
        JsonNode python = generarOk(9105L, "Quiero aprender Python para analizar datos", List.of("INGENIERIA_SISTEMAS"),
                "PRINCIPIANTE");
        JsonNode liderazgo = generarOk(9106L, "Quiero liderar equipos y gestionar proyectos",
                List.of("ADMINISTRACION_EMPRESAS"), "PRINCIPIANTE");

        List<String> titulosPython = titulos(python);
        List<String> titulosLiderazgo = titulos(liderazgo);
        assertThat(titulosPython).anyMatch(t -> t.contains("Python"));
        assertThat(titulosLiderazgo).anyMatch(t -> t.contains("Proyectos") || t.contains("Liderazgo"));
        assertThat(titulosPython).doesNotContainAnyElementsOf(titulosLiderazgo);

        // cada curso trae la razon, su detalle completo, y el roadmap trae su resumen
        assertThat(python.get("resumen").asText()).isNotBlank();
        assertThat(python.get("generadoPor").asText()).isEqualTo("RESPALDO");
        for (JsonNode c : python.get("cursos")) {
            assertThat(c.get("razon").asText()).isNotBlank();
            assertThat(c.get("duracionHoras").asInt()).isGreaterThan(0);
            assertThat(c.get("temario").size()).isGreaterThan(0);
            assertThat(c.get("linkContenido").asText()).startsWith("https://");
            assertThat(c.get("totalPreguntasExamen").asInt()).isEqualTo(3);
        }
    }

    @Test
    void unaRutaRespetaElOrdenDeLosPrerequisitos() throws Exception {
        JsonNode ruta = generarOk(9107L, "Quiero dominar Python avanzado para desarrollar aplicaciones",
                List.of("INGENIERIA_SISTEMAS"), "PRINCIPIANTE");
        Map<Long, Integer> ordenPorCurso = new HashMap<>();
        ruta.get("cursos").forEach(c -> ordenPorCurso.put(c.get("cursoId").asLong(), c.get("orden").asInt()));
        for (JsonNode c : ruta.get("cursos")) {
            for (JsonNode prerequisito : c.get("prerequisitoIds")) {
                Integer ordenPrerequisito = ordenPorCurso.get(prerequisito.asLong());
                if (ordenPrerequisito != null) {
                    assertThat(ordenPrerequisito).isLessThan(c.get("orden").asInt());
                }
            }
        }
    }

    // ------------------------------------------------------------- examen

    @Test
    void elExamenNoRegalaLasRespuestasYSeCalificaEnElServidor() throws Exception {
        long cursoId = idDeCurso("Python desde Cero");

        String examen = mockMvc.perform(get("/api/cursos/" + cursoId + "/examen").header("Authorization", token(9108L)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(examen).doesNotContain("respuestaCorrecta").doesNotContain("correcta");
        JsonNode examenJson = objectMapper.readTree(examen);
        assertThat(examenJson.get("preguntas")).hasSize(3);
        assertThat(examenJson.get("minimoAprobacion").asInt()).isEqualTo(70);
        examenJson.get("preguntas").forEach(p -> assertThat(p.get("opciones")).hasSize(4));

        // faltan respuestas: se rechaza
        presentar(cursoId, 9108L, "[0, 1]", 400);

        // en blanco = fallo
        JsonNode enBlanco = objectMapper.readTree(presentar(cursoId, 9108L, "[-1, -1, -1]", 200));
        assertThat(enBlanco.get("aprobado").asBoolean()).isFalse();
        assertThat(enBlanco.get("aciertos").asInt()).isZero();

        // 2 de 3 = 67 %, no llega al 70 % y no completa
        JsonNode dosDeTres = objectMapper.readTree(presentar(cursoId, 9108L, "[2, 1, 2]", 200));
        assertThat(dosDeTres.get("porcentaje").asInt()).isEqualTo(67);
        assertThat(dosDeTres.get("aprobado").asBoolean()).isFalse();
        assertThat(dosDeTres.get("cursoCompletado").asBoolean()).isFalse();
        // el resultado tampoco dice cual era la correcta
        assertThat(dosDeTres.toString()).doesNotContain("respuestaCorrecta");

        // las tres bien (Python desde Cero: 2, 1, 1) aprueba y completa
        JsonNode aprobado = objectMapper.readTree(presentar(cursoId, 9108L, "[2, 1, 1]", 200));
        assertThat(aprobado.get("aprobado").asBoolean()).isTrue();
        assertThat(aprobado.get("cursoCompletado").asBoolean()).isTrue();

        // y queda en su historial
        JsonNode completados = leer("/api/cursos/completados?usuarioId=9108", 9108L);
        assertThat(StreamSupport.stream(completados.spliterator(), false)
                .anyMatch(c -> c.get("cursoId").asLong() == cursoId)).isTrue();
    }

    @Test
    void elPublicadorNoPuedeGuardarUnExamenMalHecho() throws Exception {
        crearCursoConExamen("Curso con 2 preguntas", 9109L, List.of(pregunta("a", "b", "c", "d"), pregunta("a", "b", "c", "d")), 400);
        crearCursoConExamen("Curso con opciones repetidas", 9109L,
                List.of(pregunta("a", "a", "c", "d"), pregunta("a", "b", "c", "d"), pregunta("a", "b", "c", "d")), 400);
        crearCursoConExamen("Curso bien armado", 9109L,
                List.of(pregunta("a", "b", "c", "d"), pregunta("a", "b", "c", "d"), pregunta("a", "b", "c", "d")), 201);
    }

    @Test
    void unCursoSinExamenOQueYaNoEstaDisponibleNoSePuedeCompletar() throws Exception {
        // sin examen: se publica, pero nadie lo puede completar
        Map<String, Object> cuerpo = cuerpoCurso("Curso sin examen todavia", 9110L, null);
        String creado = mockMvc.perform(post("/api/cursos").header("Authorization", token(9110L))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sinExamen = objectMapper.readTree(creado).get("id").asLong();
        presentar(sinExamen, 9111L, "[0, 0, 0]", 409);

        // dado de baja: tampoco
        long cursoId = idDeCurso("Redes de Computadores");
        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja").header("Authorization", token(11L)))
                .andExpect(status().isNoContent());
        presentar(cursoId, 9111L, "[1, 1, 1]", 409);
    }

    // ------------------------------------------------------------- usuario de demostracion

    @Test
    void elUsuarioDeDemostracionYaTieneRoadmapYCursosAprobados() throws Exception {
        JsonNode roadmap = leer("/api/roadmap/mio?usuarioId=5", 5L);
        assertThat(roadmap.get("cursos").size()).isGreaterThanOrEqualTo(4);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");

        JsonNode completados = leer("/api/cursos/completados?usuarioId=5", 5L);
        assertThat(completados.size()).isEqualTo(3);

        // lo aprobado son los primeros cursos de su ruta, asi el avance se ve desde el principio del camino
        Set<Long> aprobados = new HashSet<>();
        completados.forEach(c -> aprobados.add(c.get("cursoId").asLong()));
        List<Long> primeros = new ArrayList<>();
        roadmap.get("cursos").forEach(c -> primeros.add(c.get("cursoId").asLong()));
        assertThat(aprobados).containsExactlyInAnyOrderElementsOf(primeros.subList(0, 3));
    }

    // ------------------------------------------------------------- ayudas

    private JsonNode leer(String url, long usuarioId) throws Exception {
        String cuerpo = mockMvc.perform(get(url).header("Authorization", token(usuarioId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    private long idDeCurso(String titulo) throws Exception {
        for (JsonNode c : leer("/api/cursos", 9199L)) {
            if (c.get("titulo").asText().equals(titulo)) {
                return c.get("id").asLong();
            }
        }
        throw new AssertionError("no existe el curso " + titulo);
    }

    private String generar(long usuarioId, String metas, List<String> intereses, String nivel, int estadoEsperado)
            throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", metas);
        cuerpo.put("intereses", intereses);
        cuerpo.put("nivel", nivel);
        return mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode generarOk(long usuarioId, String metas, List<String> intereses, String nivel) throws Exception {
        return objectMapper.readTree(generar(usuarioId, metas, intereses, nivel, 201));
    }

    private List<String> titulos(JsonNode roadmap) {
        List<String> titulos = new ArrayList<>();
        roadmap.get("cursos").forEach(c -> titulos.add(c.get("titulo").asText()));
        return titulos;
    }

    private String presentar(long cursoId, long usuarioId, String respuestas, int estadoEsperado) throws Exception {
        return mockMvc.perform(post("/api/cursos/" + cursoId + "/examen").header("Authorization", token(usuarioId))
                        .contentType("application/json").content("{\"respuestas\": " + respuestas + "}"))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> pregunta(String a, String b, String c, String d) {
        Map<String, Object> p = new HashMap<>();
        p.put("enunciado", "Una pregunta cualquiera");
        p.put("opciones", List.of(a, b, c, d));
        p.put("respuestaCorrecta", 0);
        return p;
    }

    private Map<String, Object> cuerpoCurso(String titulo, long publicadorId, List<Map<String, Object>> examen) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "IDIOMAS");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorId);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", 8);
        cuerpo.put("temario", List.of("Tema 1", "Tema 2"));
        if (examen != null) {
            cuerpo.put("examen", examen);
        }
        return cuerpo;
    }

    private void crearCursoConExamen(String titulo, long publicadorId, List<Map<String, Object>> examen, int estado)
            throws Exception {
        mockMvc.perform(post("/api/cursos").header("Authorization", token(publicadorId))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cuerpoCurso(titulo, publicadorId, examen))))
                .andExpect(status().is(estado));
    }

    private static String token(long usuarioId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        // el token de usuarios-service trae el rol en "rol" (texto); el usuario 11 es PUBLICADOR del catalogo, el 3 es ADMIN
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId))
                .claim("rol", usuarioId == 3L ? "ADMIN" : usuarioId >= 9000L ? "USUARIO" : "PUBLICADOR")
                .signWith(key).compact();
    }
}
