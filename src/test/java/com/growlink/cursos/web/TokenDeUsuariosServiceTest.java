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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// los otros tests firman el rol como lista ("roles"), pero asi NO lo firma usuarios-service:
// usuarios-service manda un solo claim de texto, "rol". Esta prueba usa el token con la forma
// real, y es la que habria atrapado que un ADMIN no contaba como admin en cursos-service
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenDeUsuariosServiceTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String tokenComoUsuariosService(long usuarioId, String rol) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(String.valueOf(usuarioId))
                .claim("rol", rol)
                .signWith(key)
                .compact();
    }

    @Test
    void unAdminDeUsuariosServiceSiPuedeDarDeBajaElCursoDeOtro() throws Exception {
        Long cursoId = crearCursoComo(3101L);

        // un publicador que no es el dueno sigue sin poder
        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja")
                        .header("Authorization", tokenComoUsuariosService(3102L, "PUBLICADOR")))
                .andExpect(status().isForbidden());

        // el admin si
        mockMvc.perform(patch("/api/cursos/" + cursoId + "/baja")
                        .header("Authorization", tokenComoUsuariosService(3, "ADMIN")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cursos/" + cursoId)
                        .header("Authorization", tokenComoUsuariosService(3, "ADMIN")))
                .andExpect(jsonPath("$.activo").value(false));
    }

    private Long crearCursoComo(long publicadorId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("titulo", "Curso del token real");
        body.put("categoria", "DERECHO");
        body.put("nivel", "PRINCIPIANTE");
        body.put("habilidadIds", List.of());
        body.put("publicadorUsuarioId", publicadorId);
        body.put("prerequisitoIds", List.of());

        String respuesta = mockMvc.perform(post("/api/cursos")
                        .header("Authorization", tokenComoUsuariosService(publicadorId, "PUBLICADOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) objectMapper.readValue(respuesta, Map.class).get("id")).longValue();
    }
}
