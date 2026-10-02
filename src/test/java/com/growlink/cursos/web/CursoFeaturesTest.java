package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Cubre lo nuevo: catalogo cerrado de habilidades (HU-06 parte), HU-08 (editar),
// HU-09 (baja logica), HU-10 (estado-roadmap), HU-13 (catalogo con filtros),
// HU-14/15 (completar cursos) y HU-11/12 (roadmap, modo de respaldo sin API key).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CursoFeaturesTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String token(long userId, String... roles) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", List.of(roles))
                .signWith(key)
                .compact();
    }

    @Test
    void catalogoDeHabilidadesFiltraPorCategoria() throws Exception {
        mockMvc.perform(get("/api/habilidades").param("categoria", "INGENIERIA_SISTEMAS")
                        .header("Authorization", token(9001, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre", hasItem("Programación")))
                .andExpect(jsonPath("$[*].categoria", hasItem("INGENIERIA_SISTEMAS")));
    }

    @Test
    void sinTokenSeRechaza() throws Exception {
        // sin Authorization, Spring Security trata la peticion como anonima
        // y la rechaza en la capa de autorizacion (403)
        mockMvc.perform(get("/api/cursos").param("publicadorUsuarioId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noSePuedeCrearCursoConHabilidadDeOtraCategoria() throws Exception {
        Long habilidadDeSistemas = habilidadIdPorNombre("INGENIERIA_SISTEMAS", "Programación", 9002);

        Map<String, Object> body = new HashMap<>();
        body.put("titulo", "Derecho I");
        body.put("categoria", "DERECHO");
        body.put("nivel", "PRINCIPIANTE");
        body.put("habilidadIds", List.of(habilidadDeSistemas));
        body.put("publicadorUsuarioId", 2000L);
        body.put("prerequisitoIds", List.of());

        mockMvc.perform(post("/api/cursos")
                        .header("Authorization", token(2000, "PROFESSOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("categoria")));
    }

    @Test
    void hu08EditarCurso_soloDuenoOAdmin() throws Exception {
        Long habilidadId = habilidadIdPorNombre("MATEMATICAS", "Cálculo", 9003);
        Long cursoId = crearCurso("Calculo I", "MATEMATICAS", "PRINCIPIANTE", List.of(habilidadId), 2001L);

        Map<String, Object> edicion = new HashMap<>();
        edicion.put("titulo", "Calculo I (editado)");
        edicion.put("descripcion", "nueva descripcion");
        edicion.put("nivel", "INTERMEDIO");
        edicion.put("habilidadIds", List.of(habilidadId));
        edicion.put("linkContenido", "http://ejemplo.com");

        // otro usuario, no dueño ni admin: rechazado
        mockMvc.perform(put("/api/cursos/" + cursoId)
                        .header("Authorization", token(2002, "PROFESSOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(edicion)))
                .andExpect(status().isForbidden());

        // el dueño si puede
        mockMvc.perform(put("/api/cursos/" + cursoId)
                        .header("Authorization", token(2001, "PROFESSOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(edicion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Calculo I (editado)"))
                .andExpect(jsonPath("$.nivel").value("INTERMEDIO"));
    }

    @Test
    void hu09BajaLogica_excluyeDelCatalogoPeroNoDeMisCursos() throws Exception {
        Long cursoId = crearCurso("Topografia", "INGENIERIA_CIVIL", "PRINCIPIANTE", List.of(), 2003L);

        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja")
                        .header("Authorization", token(2003, "PROFESSOR")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cursos").param("categoria", "INGENIERIA_CIVIL")
                        .header("Authorization", token(9004, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + cursoId + ")]").isEmpty());

        mockMvc.perform(get("/api/cursos").param("publicadorUsuarioId", "2003")
                        .header("Authorization", token(2003, "PROFESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + cursoId + ")].activo").value(hasItem(false)));
    }

    @Test
    void hu10EstadoRoadmapIndicaCualesCursosYaNoEstanActivos() throws Exception {
        Long activo = crearCurso("Logistica", "INGENIERIA_INDUSTRIAL", "PRINCIPIANTE", List.of(), 2004L);
        Long inactivo = crearCurso("Control de calidad", "INGENIERIA_INDUSTRIAL", "PRINCIPIANTE", List.of(), 2004L);

        mockMvc.perform(patch("/api/cursos/" + inactivo + "/baja")
                        .header("Authorization", token(2004, "PROFESSOR")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cursos/estado-roadmap")
                        .param("ids", activo + "," + inactivo)
                        .header("Authorization", token(9005, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiereRegenerar").value(true))
                .andExpect(jsonPath("$.cursoIdsInactivos", hasItem(inactivo.intValue())));
    }

    @Test
    void hu1415CompletarCursoYVerHistorial() throws Exception {
        Long cursoId = crearCurso("Ingles basico", "IDIOMAS", "PRINCIPIANTE", List.of(), 2005L);
        long usuarioId = 3001L;

        mockMvc.perform(post("/api/cursos/" + cursoId + "/completar")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content("{\"usuarioId\": " + usuarioId + "}"))
                .andExpect(status().isCreated());

        // completar dos veces se rechaza
        mockMvc.perform(post("/api/cursos/" + cursoId + "/completar")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content("{\"usuarioId\": " + usuarioId + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/cursos/completados").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.cursoId == " + cursoId + ")].disponible").value(hasItem(true)));

        // se da de baja el curso, pero el historial lo sigue mostrando (ya no disponible)
        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja")
                        .header("Authorization", token(2005, "PROFESSOR")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cursos/completados").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.cursoId == " + cursoId + ")].disponible").value(hasItem(false)));
    }

    @Test
    void hu1112RoadmapConModoDeRespaldoRespetaPrerequisitos() throws Exception {
        Long base = crearCurso("Programacion I", "INGENIERIA_SISTEMAS", "PRINCIPIANTE", List.of(), 2006L);
        Long avanzado = crearCurso("Estructuras de Datos", "INGENIERIA_SISTEMAS", "INTERMEDIO", List.of(), 2006L);

        mockMvc.perform(put("/api/cursos/" + avanzado + "/prerequisitos")
                        .header("Authorization", token(2006, "PROFESSOR"))
                        .contentType("application/json")
                        .content("{\"prerequisitoIds\": [" + base + "]}"))
                .andExpect(status().isOk());

        long usuarioId = 4001L;
        Map<String, Object> generar = new HashMap<>();
        generar.put("usuarioId", usuarioId);
        generar.put("metas", "Quiero ser desarrollador backend");
        generar.put("intereses", List.of("INGENIERIA_SISTEMAS"));
        generar.put("nivel", "INTERMEDIO");

        String respuesta = mockMvc.perform(post("/api/roadmap/generar")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(generar)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cursos[?(@.cursoId == " + base + ")].orden").exists())
                .andExpect(jsonPath("$.cursos[?(@.cursoId == " + avanzado + ")].orden").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // el prerequisito (base) debe quedar antes que el dependiente (avanzado)
        Map<String, Object> roadmap = objectMapper.readValue(respuesta, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cursos = (List<Map<String, Object>>) roadmap.get("cursos");
        int ordenBase = ordenDe(cursos, base);
        int ordenAvanzado = ordenDe(cursos, avanzado);
        org.junit.jupiter.api.Assertions.assertTrue(ordenBase < ordenAvanzado,
                "el prerequisito debe aparecer antes que el curso que lo requiere");

        // el catalogo es global (comparte categoria/nivel con cursos de otras
        // pruebas), asi que solo verificamos que nuestros dos cursos esten y
        // que el prerequisito (base) quede antes que el dependiente (avanzado)
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", String.valueOf(usuarioId))
                        .header("Authorization", token(usuarioId, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursos[?(@.cursoId == " + base + ")]").exists())
                .andExpect(jsonPath("$.cursos[?(@.cursoId == " + avanzado + ")]").exists());
    }

    @Test
    void sugerirPrerequisitosUsaModoDeRespaldoYSugiereLosDeMenorNivel() throws Exception {
        Long basico = crearCurso("Derecho Civil I", "DERECHO", "PRINCIPIANTE", List.of(), 2007L);
        Long intermedio = crearCurso("Derecho Constitucional", "DERECHO", "INTERMEDIO", List.of(), 2007L);
        Long basicoInactivo = crearCurso("Derecho Civil II (descontinuado)", "DERECHO", "PRINCIPIANTE", List.of(),
                2007L);

        mockMvc.perform(patch("/api/cursos/" + basicoInactivo + "/baja")
                        .header("Authorization", token(2007, "PROFESSOR")))
                .andExpect(status().isNoContent());

        Map<String, Object> body = new HashMap<>();
        body.put("categoria", "DERECHO");
        body.put("nivel", "AVANZADO");
        body.put("titulo", "Derecho Procesal");
        body.put("descripcion", "curso avanzado de procedimientos judiciales");

        // sin CLAUDE_API_KEY en el perfil de test, esto siempre cae al modo de
        // respaldo: sugiere los cursos activos de menor nivel en la categoria
        mockMvc.perform(post("/api/cursos/sugerir-prerequisitos")
                        .header("Authorization", token(9006, "STUDENT"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modoRespaldo").value(true))
                .andExpect(jsonPath("$.prerequisitos.length()").value(1))
                .andExpect(jsonPath("$.prerequisitos[*].id").value(hasItem(basico.intValue())))
                .andExpect(jsonPath("$.prerequisitos[*].id").value(not(hasItem(intermedio.intValue()))))
                .andExpect(jsonPath("$.prerequisitos[*].id").value(not(hasItem(basicoInactivo.intValue()))));
    }

    @Test
    void sugerirPrerequisitosSinCandidatosActivosDevuelveListaVacia() throws Exception {
        // categoria sin cursos, ni del catalogo sembrado por CursoCatalogoSeeder
        // ni de otras pruebas de esta clase
        Map<String, Object> body = new HashMap<>();
        body.put("categoria", "INGENIERIA_ELECTRONICA");
        body.put("nivel", "PRINCIPIANTE");
        body.put("titulo", "Circuitos I");

        mockMvc.perform(post("/api/cursos/sugerir-prerequisitos")
                        .header("Authorization", token(9007, "STUDENT"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modoRespaldo").value(true))
                .andExpect(jsonPath("$.prerequisitos").isEmpty());
    }

    @Test
    void roadmapMioSinRoadmapDevuelve404() throws Exception {
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", "4999")
                        .header("Authorization", token(4999, "STUDENT")))
                .andExpect(status().isNotFound());
    }

    private int ordenDe(List<Map<String, Object>> cursos, Long cursoId) {
        return cursos.stream()
                .filter(c -> ((Number) c.get("cursoId")).longValue() == cursoId)
                .map(c -> ((Number) c.get("orden")).intValue())
                .findFirst()
                .orElseThrow();
    }

    private Long habilidadIdPorNombre(String categoria, String nombre, long tokenUserId) throws Exception {
        String response = mockMvc.perform(get("/api/habilidades").param("categoria", categoria)
                        .header("Authorization", token(tokenUserId, "STUDENT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> habilidades = objemapperAListaDeMapas(response);
        return habilidades.stream()
                .filter(h -> nombre.equals(h.get("nombre")))
                .map(h -> ((Number) h.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objemapperAListaDeMapas(String json) throws Exception {
        return objectMapper.readValue(json, List.class);
    }

    private Long crearCurso(String titulo, String categoria, String nivel, List<Long> habilidadIds,
                             Long publicadorUsuarioId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("titulo", titulo);
        body.put("categoria", categoria);
        body.put("nivel", nivel);
        body.put("habilidadIds", habilidadIds);
        body.put("publicadorUsuarioId", publicadorUsuarioId);
        body.put("prerequisitoIds", List.of());

        String response = mockMvc.perform(post("/api/cursos")
                        .header("Authorization", token(publicadorUsuarioId, "PROFESSOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number id = (Number) objectMapper.readValue(response, Map.class).get("id");
        return id.longValue();
    }
}
