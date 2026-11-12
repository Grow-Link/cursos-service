package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.application.MetricasDeCursos;
import com.growlink.cursos.domain.Categoria;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO observabilidad (negocio): growlink.cursos.completados cuenta lo que parece que cuenta (ver
// MetricasDeCursos). Un curso completado de verdad suma uno, con su via y su area; reprobar, repetir, no tener
// permiso, la siembra del arranque o una transaccion que se deshace no suman nada.
//
// Contexto propio: base nueva (para comprobar que la siembra del arranque no cuenta) y sin el tope por IP (estas
// peticiones no gastan el cupo que comparten las demas pruebas).
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursoscompletados;MODE=PostgreSQL",
        "seguridad.limite-ip.maximo-por-minuto=0"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CursosCompletadosTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    // el usuario de demostracion que siembra CursoCatalogoSeeder, con 3 cursos ya aprobados
    private static final long USUARIO_DEMO = 5L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry registro;
    @Autowired
    private MetricasDeCursos metricas;
    @Autowired
    private CursoCompletadoRepository completadoRepository;
    @Autowired
    private PlatformTransactionManager transacciones;

    // va primero: tiene que ver la medida tal como queda despues del arranque, antes de que otra prueba sume
    @Test
    @Order(1)
    void laSiembraDelArranqueNoCuenta() {
        // la siembra si corrio: el usuario de demostracion tiene cursos aprobados...
        assertThat(completadoRepository.findByUsuarioId(USUARIO_DEMO)).isNotEmpty();
        // ...pero eso no es alguien completando un curso
        assertThat(total()).isZero();
        // y todas las combinaciones ya existen desde el arranque, en 0, para que el tablero no diga "sin datos"
        assertThat(registro.find(MetricasDeCursos.COMPLETADOS).counters())
                .hasSize(2 * Categoria.values().length);
    }

    @Test
    void aprobarElExamenCuentaUnCursoCompletadoDeSuAreaYSoloUnaVez() throws Exception {
        Long cursoId = crearCursoConExamen("Frances para viajar", "IDIOMAS", 2101L);
        long usuario = 3101L;
        double antes = completados("examen", "IDIOMAS");
        double totalAntes = total();

        // reprobar no completa el curso
        presentar(cursoId, usuario, List.of(0, 0, 0), false);
        assertThat(completados("examen", "IDIOMAS")).isEqualTo(antes);

        // aprobarlo si, y cuenta en su area
        presentar(cursoId, usuario, List.of(1, 1, 1), true);
        assertThat(completados("examen", "IDIOMAS") - antes).isEqualTo(1);

        // presentarlo otra vez y aprobar de nuevo no es otro curso completado
        presentar(cursoId, usuario, List.of(1, 1, 1), true);
        assertThat(completados("examen", "IDIOMAS") - antes).isEqualTo(1);
        // y no se conto en ninguna otra parte
        assertThat(total() - totalAntes).isEqualTo(1);
    }

    @Test
    void unAdminQueLoMarcaCuentaComoViaAdminYRepetirloNoSuma() throws Exception {
        Long cursoId = crearCursoConExamen("Contratos para empezar", "DERECHO", 2102L);
        double antes = completados("admin", "DERECHO");

        completarComoAdmin(cursoId, 3102L, 201);
        assertThat(completados("admin", "DERECHO") - antes).isEqualTo(1);

        // ya estaba completado: 409 y no se cuenta otra vez
        completarComoAdmin(cursoId, 3102L, 409);
        assertThat(completados("admin", "DERECHO") - antes).isEqualTo(1);
    }

    @Test
    void sinSerAdminNoSeCompletaNiSeCuenta() throws Exception {
        Long cursoId = crearCursoConExamen("Algebra de bolsillo", "MATEMATICAS", 2103L);
        double totalAntes = total();

        mockMvc.perform(post("/api/cursos/" + cursoId + "/completar")
                        .header("Authorization", token(3103L, "STUDENT"))
                        .contentType("application/json")
                        .content("{\"usuarioId\": 3103}"))
                .andExpect(status().isForbidden());

        assertThat(total()).isEqualTo(totalAntes);
    }

    @Test
    void soloCuentaLoQueSeConfirmaEnLaBase() {
        double antes = completados("examen", "INGENIERIA_CIVIL");
        TransactionTemplate transaccion = new TransactionTemplate(transacciones);

        // la transaccion se deshace: el curso no quedo completado, no se cuenta
        transaccion.executeWithoutResult(estado -> {
            metricas.cursoCompletado(MetricasDeCursos.VIA_EXAMEN, Categoria.INGENIERIA_CIVIL);
            estado.setRollbackOnly();
        });
        assertThat(completados("examen", "INGENIERIA_CIVIL")).isEqualTo(antes);

        // se confirma: ahora si
        transaccion.executeWithoutResult(estado ->
                metricas.cursoCompletado(MetricasDeCursos.VIA_EXAMEN, Categoria.INGENIERIA_CIVIL));
        assertThat(completados("examen", "INGENIERIA_CIVIL") - antes).isEqualTo(1);
    }

    // ---------------------------------------------------------------- utilidades

    private void presentar(Long cursoId, long usuarioId, List<Integer> respuestas, boolean aprobado)
            throws Exception {
        mockMvc.perform(post("/api/cursos/" + cursoId + "/examen")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("respuestas", respuestas))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aprobado").value(aprobado));
    }

    private void completarComoAdmin(Long cursoId, long usuarioId, int estadoEsperado) throws Exception {
        mockMvc.perform(post("/api/cursos/" + cursoId + "/completar")
                        .header("Authorization", token(1L, "ADMIN"))
                        .contentType("application/json")
                        .content("{\"usuarioId\": " + usuarioId + "}"))
                .andExpect(status().is(estadoEsperado));
    }

    // un curso con examen de 3 preguntas donde la opcion correcta de todas es la segunda (indice 1)
    private Long crearCursoConExamen(String titulo, String categoria, Long publicadorUsuarioId) throws Exception {
        List<Map<String, Object>> examen = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            examen.add(Map.of("enunciado", "Pregunta " + i + " de " + titulo,
                    "opciones", List.of("a", "b", "c", "d"), "respuestaCorrecta", 1));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("titulo", titulo);
        body.put("categoria", categoria);
        body.put("nivel", "PRINCIPIANTE");
        body.put("habilidadIds", List.of());
        body.put("publicadorUsuarioId", publicadorUsuarioId);
        body.put("prerequisitoIds", List.of());
        body.put("duracionHoras", 10);
        body.put("temario", List.of("Tema uno", "Tema dos"));
        body.put("examen", examen);

        String respuesta = mockMvc.perform(post("/api/cursos")
                        .header("Authorization", token(publicadorUsuarioId, "PUBLICADOR"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) objectMapper.readValue(respuesta, Map.class).get("id")).longValue();
    }

    private static String token(long usuarioId, String... roles) {
        SecretKey llave = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(String.valueOf(usuarioId))
                .claim("roles", List.of(roles))
                .signWith(llave)
                .compact();
    }

    private double completados(String via, String categoria) {
        Counter contador = registro.find(MetricasDeCursos.COMPLETADOS)
                .tag("via", via).tag("categoria", categoria).counter();
        return contador == null ? 0 : contador.count();
    }

    private double total() {
        return registro.find(MetricasDeCursos.COMPLETADOS).counters().stream().mapToDouble(Counter::count).sum();
    }
}
