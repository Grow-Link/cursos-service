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
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CursoControllerTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String token(Long userId, String... roles) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.of(roles))
                .signWith(key)
                .compact();
    }

    // cada prueba usa su propio publicadorUsuarioId, porque las 3 pruebas
    // comparten la misma base H2 (no se limpia entre una y otra) y si dos
    // pruebas usaran el mismo id se contaminarian los conteos entre si
    @Test
    void publicadorVeSusCursosYNoLosDeOtroPublicador() throws Exception {
        crearCurso("Java basico", 901L, null);
        crearCurso("Spring Boot", 901L, null);
        crearCurso("Curso de otro publicador", 902L, null);

        mockMvc.perform(get("/api/cursos").param("publicadorUsuarioId", "901")
                        .header("Authorization", token(901L, "PROFESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unCursoNoPuedeSerPrerequisitoDeSiMismo() throws Exception {
        Long id = crearCurso("Curso solitario", 903L, null);

        mockMvc.perform(put("/api/cursos/" + id + "/prerequisitos")
                        .header("Authorization", token(903L, "PROFESSOR"))
                        .contentType("application/json")
                        .content("{\"prerequisitoIds\": [" + id + "]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void intentarCrearUnCicloDeDosCursosSeRechaza() throws Exception {
        // A requiere B primero, eso es valido
        Long cursoA = crearCurso("Curso A", 904L, null);
        Long cursoB = crearCurso("Curso B", 904L, List.of(cursoA)); // B requiere A

        // ahora intentamos que A requiera B, eso cierra el ciclo A -> B -> A
        mockMvc.perform(put("/api/cursos/" + cursoA + "/prerequisitos")
                        .header("Authorization", token(904L, "PROFESSOR"))
                        .contentType("application/json")
                        .content("{\"prerequisitoIds\": [" + cursoB + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ciclo")));

        // y A se quedo sin prerequisitos, el rechazo no dejo nada a medias
        mockMvc.perform(get("/api/cursos/" + cursoA)
                        .header("Authorization", token(904L, "PROFESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisitoIds.length()").value(0));
    }

    private Long crearCurso(String titulo, Long publicadorUsuarioId, java.util.List<Long> prerequisitoIds) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("titulo", titulo);
        body.put("categoria", "INGENIERIA_SISTEMAS");
        body.put("nivel", "PRINCIPIANTE");
        body.put("habilidadIds", java.util.List.of());
        body.put("publicadorUsuarioId", publicadorUsuarioId);
        body.put("prerequisitoIds", prerequisitoIds == null ? java.util.List.of() : prerequisitoIds);
        body.put("duracionHoras", 20);

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
