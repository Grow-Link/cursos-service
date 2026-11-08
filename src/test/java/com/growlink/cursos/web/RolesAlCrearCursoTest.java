package com.growlink.cursos.web;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO seguridad: quien puede hacer que con los cursos, por endpoint y por rol. El catalogo es lo que ve toda
// la plataforma, asi que publicar en el no puede ser cosa de cualquiera que tenga cuenta: hace falta PUBLICADOR o
// ADMIN (ver CursoController.crear y AuthenticatedUser.exigirPuedePublicar).
//
// Va en modo legacy, que es el de por defecto: aqui el rol viene en el JWT compartido. La MISMA regla con el rol
// resuelto en usuarios-service (modo oidc) se prueba en IdentidadDesdeUsuariosServiceTest, para que quede claro
// que la regla es una sola y lo que cambia es de donde sale el rol.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosroles;MODE=PostgreSQL")
class RolesAlCrearCursoTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final AtomicInteger SIGUIENTE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ POST /api/cursos (publicar)

    @Test
    void publicarSinTokenSeRechaza() throws Exception {
        int estado = mockMvc.perform(post("/api/cursos").contentType("application/json")
                        .content(objectMapper.writeValueAsString(cuerpo(4001L))))
                .andReturn().getResponse().getStatus();

        assertThat(estado).isIn(401, 403);
        assertThat(cuantosCursosHay(4001L)).as("y no se creo nada").isZero();
    }

    @Test
    void unUsuarioComunNoPuedePublicarYNoSeCreaNada() throws Exception {
        long usuario = 4002L;

        mockMvc.perform(post("/api/cursos").header("Authorization", token(usuario, "USUARIO"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo(usuario))))
                .andExpect(status().isForbidden());

        assertThat(cuantosCursosHay(usuario)).as("no se creo nada").isZero();
    }

    @Test
    void unPublicadorSiPuedePublicar() throws Exception {
        long publicador = 4003L;

        mockMvc.perform(post("/api/cursos").header("Authorization", token(publicador, "PUBLICADOR"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo(publicador))))
                .andExpect(status().isCreated());

        assertThat(cuantosCursosHay(publicador)).isEqualTo(1);
    }

    @Test
    void unAdminTambienPuedePublicar() throws Exception {
        long admin = 3L;

        mockMvc.perform(post("/api/cursos").header("Authorization", token(admin, "ADMIN"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo(admin))))
                .andExpect(status().isCreated());

        assertThat(cuantosCursosHay(admin)).isEqualTo(1);
    }

    @Test
    void unUsuarioComunNoCreaNadaMandeLoQueMande() throws Exception {
        long usuario = 4004L;
        Map<String, Object> cuerpoRoto = new HashMap<>();
        cuerpoRoto.put("titulo", "");

        // OJO con el codigo: Spring valida el cuerpo (@Valid) ANTES de entrar al metodo, asi que un cuerpo mal
        // armado se queda en 400 y nunca llega a la comprobacion del rol. Da igual para lo que importa: por un
        // camino o por el otro la peticion se rechaza y no se crea ningun curso
        mockMvc.perform(post("/api/cursos").header("Authorization", token(usuario, "USUARIO"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpoRoto)))
                .andExpect(status().isBadRequest());

        // con el cuerpo bien armado si se llega al rol, y ahi es 403
        mockMvc.perform(post("/api/cursos").header("Authorization", token(usuario, "USUARIO"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo(usuario))))
                .andExpect(status().isForbidden());

        assertThat(cuantosCursosHay(usuario)).isZero();
    }

    // ------------------------------------------------------------------ GET /api/cursos (leer el catalogo)

    @Test
    void elCatalogoLoLeeCualquieraQueEsteAutenticado() throws Exception {
        for (String rol : List.of("USUARIO", "PUBLICADOR", "ADMIN")) {
            assertThat(mockMvc.perform(get("/api/cursos").header("Authorization", token(4100L, rol)))
                    .andReturn().getResponse().getStatus()).as("el catalogo con rol %s", rol).isEqualTo(200);
        }
        // sin token no
        assertThat(mockMvc.perform(get("/api/cursos")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    // ------------------------------------------------------------------ PATCH /api/cursos/{id}/baja

    @Test
    void darDeBajaEsDelDuenoODeUnAdmin() throws Exception {
        long dueno = 4201L;
        long otroPublicador = 4202L;
        Long curso = crearComo(dueno, "PUBLICADOR");

        // un usuario comun, no
        mockMvc.perform(patch("/api/cursos/" + curso + "/baja").header("Authorization", token(4203L, "USUARIO")))
                .andExpect(status().isForbidden());
        // otro publicador que no es el dueno, tampoco
        mockMvc.perform(patch("/api/cursos/" + curso + "/baja")
                        .header("Authorization", token(otroPublicador, "PUBLICADOR")))
                .andExpect(status().isForbidden());
        // sin token, tampoco
        assertThat(mockMvc.perform(patch("/api/cursos/" + curso + "/baja"))
                .andReturn().getResponse().getStatus()).isIn(401, 403);

        // el dueno si
        mockMvc.perform(patch("/api/cursos/" + curso + "/baja").header("Authorization", token(dueno, "PUBLICADOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    void unAdminPuedeDarDeBajaElCursoDeOtro() throws Exception {
        Long curso = crearComo(4301L, "PUBLICADOR");

        mockMvc.perform(patch("/api/cursos/" + curso + "/baja").header("Authorization", token(3L, "ADMIN")))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ ayudas

    private Long crearComo(long publicador, String rol) throws Exception {
        String respuesta = mockMvc.perform(post("/api/cursos").header("Authorization", token(publicador, rol))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo(publicador))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private int cuantosCursosHay(long publicadorId) throws Exception {
        String respuesta = mockMvc.perform(get("/api/cursos").param("publicadorUsuarioId", String.valueOf(publicadorId))
                        .header("Authorization", token(3L, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).size();
    }

    private static Map<String, Object> cuerpo(long publicadorUsuarioId) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", "Curso de roles " + SIGUIENTE.incrementAndGet());
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicadorUsuarioId);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", 20);
        return cuerpo;
    }

    private static String token(long usuarioId, String rol) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId)).claim("rol", rol)
                .signWith(key).compact();
    }
}
