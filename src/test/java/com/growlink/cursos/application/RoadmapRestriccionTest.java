package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// La restriccion del profe: cuando se quita un curso, ya no se recomienda.
// Aqui la IA es falsa a proposito, para comprobar que NO importa lo que conteste la IA:
// el catalogo que se le manda solo tiene cursos activos, y si aun asi recomienda uno dado
// de baja (o uno inventado) la respuesta se rechaza y se usa el respaldo.
// Tambien se prueba que el roadmap diga la verdad sobre quien lo genero, y que nadie pueda
// generar ni leer el roadmap de otro usuario.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// base propia, para que los cursos de otras pruebas no se mezclen con el catalogo de esta
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosrestriccion;MODE=PostgreSQL")
@Import(RoadmapRestriccionTest.IaFalsaConfig.class)
class RoadmapRestriccionTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    // reemplaza al cliente real de Claude: no sale a internet, contesta lo que cada prueba le diga
    static class IaFalsa extends ClaudeRoadmapAiClient {
        volatile Function<List<CursoGrafoNodo>, List<Long>> decide = catalogo -> List.of();
        volatile List<Long> ultimoCatalogoVisto = List.of();

        IaFalsa() {
            super("llave-falsa", "modelo-falso", new ObjectMapper());
        }

        @Override
        public List<Long> generarOrden(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
            ultimoCatalogoVisto = catalogoActivo.stream().map(CursoGrafoNodo::cursoId).toList();
            return decide.apply(catalogoActivo);
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

    @BeforeEach
    void iaPorDefecto() {
        ia.decide = catalogo -> List.of();
    }

    @Test
    void unCursoDadoDeBajaNoSeLeMandaALaIaNiSeRecomienda() throws Exception {
        Long vigente = crearCurso("Derecho Penal I", 7001L);
        Long aRetirar = crearCurso("Derecho Penal II", 7001L);

        // la IA recomienda todo lo que vea de Derecho
        ia.decide = catalogo -> catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                .map(CursoGrafoNodo::cursoId).toList();

        JsonNode primero = generar(7100L, 7100L, 201);
        assertThat(idsDelRoadmap(primero)).contains(vigente, aRetirar);
        assertThat(primero.get("generadoPor").asText()).isEqualTo("IA");

        // se da de baja el curso: el roadmap ya guardado avisa que hay que regenerar
        darDeBaja(aRetirar, 7001L);
        mockMvc.perform(get("/api/cursos/estado-roadmap").param("ids", vigente + "," + aRetirar)
                        .header("Authorization", token(7100L, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.requiereRegenerar").value(true));

        // al regenerar, la IA ni siquiera ve el curso retirado, y no queda en el roadmap nuevo
        JsonNode segundo = generar(7100L, 7100L, 201);
        assertThat(ia.ultimoCatalogoVisto).contains(vigente).doesNotContain(aRetirar);
        assertThat(idsDelRoadmap(segundo)).contains(vigente).doesNotContain(aRetirar);
    }

    @Test
    void siLaIaRecomiendaUnCursoDadoDeBajaSeRechazaYSeUsaElRespaldo() throws Exception {
        Long vigente = crearCurso("Derecho Civil I", 7002L);
        Long retirado = crearCurso("Derecho Civil II", 7002L);
        darDeBaja(retirado, 7002L);

        // una IA que "se acuerda" de un curso que ya no existe en el catalogo activo
        ia.decide = catalogo -> List.of(retirado, vigente);

        JsonNode roadmap = generar(7101L, 7101L, 201);

        assertThat(idsDelRoadmap(roadmap)).doesNotContain(retirado);
        assertThat(idsDelRoadmap(roadmap)).contains(vigente);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
    }

    @Test
    void siLaIaInventaUnCursoSeRechazaYSeUsaElRespaldo() throws Exception {
        Long vigente = crearCurso("Derecho Laboral I", 7003L);
        ia.decide = catalogo -> List.of(999_999L);

        JsonNode roadmap = generar(7102L, 7102L, 201);

        // el respaldo arma el roadmap con los cursos reales, el id inventado no entra
        // (otras pruebas de esta clase tambien dejan cursos de Derecho, por eso no se compara la lista exacta)
        assertThat(idsDelRoadmap(roadmap)).contains(vigente).doesNotContain(999_999L);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
    }

    @Test
    void siLaIaFallaSeUsaElRespaldoYSeDiceLaVerdad() throws Exception {
        crearCurso("Derecho Mercantil I", 7004L);
        ia.decide = catalogo -> {
            throw new RoadmapAiException("simulacro: Claude no contesto");
        };

        JsonNode roadmap = generar(7103L, 7103L, 201);

        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(idsDelRoadmap(roadmap)).isNotEmpty();
    }

    @Test
    void elRoadmapGuardadoTambienDiceQuienLoGenero() throws Exception {
        crearCurso("Derecho Tributario I", 7005L);
        ia.decide = catalogo -> catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                .map(CursoGrafoNodo::cursoId).toList();
        generar(7104L, 7104L, 201);

        String mio = mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", "7104")
                        .header("Authorization", token(7104L, "STUDENT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(mio).get("generadoPor").asText()).isEqualTo("IA");
    }

    @Test
    void nadieGeneraNiLeeNiCompletaALaVozDeOtroUsuario() throws Exception {
        Long curso = crearCurso("Derecho Ambiental I", 7006L);
        ia.decide = catalogo -> List.of(curso);

        // el usuario 7105 intenta generar el roadmap del 7106
        generar(7105L, 7106L, 403);
        // y leerlo
        mockMvc.perform(get("/api/roadmap/mio").param("usuarioId", "7106")
                        .header("Authorization", token(7105L, "STUDENT")))
                .andExpect(status().isForbidden());
        // y marcarle cursos como completados, o ver su historial
        mockMvc.perform(post("/api/cursos/" + curso + "/completar")
                        .header("Authorization", token(7105L, "STUDENT"))
                        .contentType("application/json")
                        .content("{\"usuarioId\": 7106}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cursos/completados").param("usuarioId", "7106")
                        .header("Authorization", token(7105L, "STUDENT")))
                .andExpect(status().isForbidden());

        // el ADMIN si puede actuar por otros
        generar(3L, 7106L, 201, "ADMIN");
    }

    // ------------------------------------------------------------------ ayudas

    private JsonNode generar(long usuarioDelToken, long usuarioDelBody, int estadoEsperado) throws Exception {
        return generar(usuarioDelToken, usuarioDelBody, estadoEsperado, "STUDENT");
    }

    private JsonNode generar(long usuarioDelToken, long usuarioDelBody, int estadoEsperado, String rol) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioDelBody);
        cuerpo.put("metas", "Quiero trabajar en derecho");
        cuerpo.put("intereses", List.of("DERECHO"));
        cuerpo.put("nivel", "PRINCIPIANTE");

        String respuesta = mockMvc.perform(post("/api/roadmap/generar")
                        .header("Authorization", token(usuarioDelToken, rol))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return respuesta.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(respuesta);
    }

    private List<Long> idsDelRoadmap(JsonNode roadmap) {
        return StreamSupport.stream(roadmap.get("cursos").spliterator(), false)
                .map(c -> c.get("cursoId").asLong()).toList();
    }

    private Long crearCurso(String titulo, long publicadorId) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorId);
        cuerpo.put("prerequisitoIds", List.of());

        String respuesta = mockMvc.perform(post("/api/cursos")
                        .header("Authorization", token(publicadorId, "PROFESSOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private void darDeBaja(Long cursoId, long publicadorId) throws Exception {
        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja")
                        .header("Authorization", token(publicadorId, "PROFESSOR")))
                .andExpect(status().isNoContent());
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
