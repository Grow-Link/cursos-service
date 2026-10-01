package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CursoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // cada prueba usa su propio publicadorUsuarioId, porque las 3 pruebas
    // comparten la misma base H2 (no se limpia entre una y otra) y si dos
    // pruebas usaran el mismo id se contaminarian los conteos entre si
    @Test
    void publicadorVeSusCursosYNoLosDeOtroPublicador() throws Exception {
        crearCurso("Java basico", 901L, null);
        crearCurso("Spring Boot", 901L, null);
        crearCurso("Curso de otro publicador", 902L, null);

        mockMvc.perform(get("/api/cursos").param("publicadorUsuarioId", "901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unCursoNoPuedeSerPrerequisitoDeSiMismo() throws Exception {
        Long id = crearCurso("Curso solitario", 903L, null);

        mockMvc.perform(put("/api/cursos/" + id + "/prerequisitos")
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
                        .contentType("application/json")
                        .content("{\"prerequisitoIds\": [" + cursoB + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ciclo")));

        // y A se quedo sin prerequisitos, el rechazo no dejo nada a medias
        mockMvc.perform(get("/api/cursos/" + cursoA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prerequisitoIds.length()").value(0));
    }

    private Long crearCurso(String titulo, Long publicadorUsuarioId, java.util.List<Long> prerequisitoIds) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("titulo", titulo);
        body.put("categoria", "BACKEND");
        body.put("nivel", "PRINCIPIANTE");
        body.put("habilidades", java.util.List.of("Java"));
        body.put("publicadorUsuarioId", publicadorUsuarioId);
        body.put("prerequisitoIds", prerequisitoIds == null ? java.util.List.of() : prerequisitoIds);

        String response = mockMvc.perform(post("/api/cursos")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Number id = (Number) objectMapper.readValue(response, Map.class).get("id");
        return id.longValue();
    }
}
