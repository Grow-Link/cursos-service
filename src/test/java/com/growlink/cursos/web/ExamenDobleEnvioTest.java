package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.application.MetricasDeCursos;
import com.growlink.cursos.domain.CursoCompletado;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HU-14: presentar dos veces el mismo examen aprobado AL MISMO TIEMPO (un doble clic, dos pestanas, un reintento del
// navegador) deja el curso completado una sola vez, y los dos envios reciben su resultado. Antes, el que perdia la
// carrera chocaba con la restriccion unica (usuarioId, cursoId) dentro de su transaccion: aunque el choque se atrapaba,
// la transaccion ya habia quedado marcada para deshacerse, y al confirmarla respondia 500 (ver ExamenService.presentar).
//
// La carrera de verdad depende de la suerte, asi que la primera prueba la arma a proposito: el otro envio ya guardo el
// curso completado, pero este alcanzo a preguntar "ya lo completo?" antes de que lo hiciera (el espia contesta que no).
// Es justo lo que ve el que pierde, con la base, la restriccion y la transaccion de verdad. La segunda prueba lo hace con
// hilos reales.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursosdobleenvio;MODE=PostgreSQL",
        "seguridad.limite-ip.maximo-por-minuto=0"
})
class ExamenDobleEnvioTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry registro;
    @SpyBean
    private CursoCompletadoRepository completados;

    @Test
    void elEnvioQuePierdeLaCarreraRecibeSuResultadoYNoUn500() throws Exception {
        Long cursoId = crearCursoConExamen("Etica profesional basica", "DERECHO", 2201L);
        long usuario = 3201L;
        // el otro envio ya dejo el curso completado...
        completados.saveAndFlush(new CursoCompletado(usuario, cursoId));
        // ...pero este pregunto antes de que lo hiciera
        doReturn(false).when(completados).existsByUsuarioIdAndCursoId(usuario, cursoId);
        double antes = completadosPorExamen("DERECHO");

        MockHttpServletResponse respuesta = presentarAprobado(cursoId, usuario);

        assertThat(respuesta.getStatus()).as(respuesta.getContentAsString()).isEqualTo(200);
        Map<?, ?> resultado = objectMapper.readValue(respuesta.getContentAsString(), Map.class);
        assertThat(resultado.get("aprobado")).isEqualTo(true);
        assertThat(resultado.get("cursoCompletado")).isEqualTo(true);
        // sigue habiendo un solo registro, y este envio no lo conto otra vez (lo conto el que gano)
        assertThat(completados.findByUsuarioId(usuario)).hasSize(1);
        assertThat(completadosPorExamen("DERECHO")).isEqualTo(antes);
    }

    @Test
    void ochoEnviosALaVezDelMismoExamenTerminanBienYCuentanUnaVez() throws Exception {
        // varias rondas, cada una con su curso y su persona, para que la carrera de verdad tenga donde darse
        for (int ronda = 0; ronda < 5; ronda++) {
            Long cursoId = crearCursoConExamen("Contratos ronda " + ronda, "DERECHO", 2300L + ronda);
            long usuario = 3300L + ronda;
            double antes = completadosPorExamen("DERECHO");

            int hilos = 8;
            ExecutorService pool = Executors.newFixedThreadPool(hilos);
            CountDownLatch arrancar = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> respuestas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                respuestas.add(pool.submit(() -> {
                    arrancar.await();
                    return presentarAprobado(cursoId, usuario);
                }));
            }
            arrancar.countDown();
            for (Future<MockHttpServletResponse> f : respuestas) {
                MockHttpServletResponse r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getStatus()).as("ronda " + ronda + ": " + r.getContentAsString()).isEqualTo(200);
                assertThat(objectMapper.readValue(r.getContentAsString(), Map.class).get("cursoCompletado"))
                        .isEqualTo(true);
            }
            pool.shutdown();

            assertThat(completados.findByUsuarioId(usuario)).as("ronda " + ronda).hasSize(1);
            assertThat(completadosPorExamen("DERECHO") - antes).as("ronda " + ronda).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- utilidades

    private MockHttpServletResponse presentarAprobado(Long cursoId, long usuarioId) throws Exception {
        return mockMvc.perform(post("/api/cursos/" + cursoId + "/examen")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content("{\"respuestas\": [1, 1, 1]}"))
                .andReturn().getResponse();
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

    private double completadosPorExamen(String categoria) {
        Counter contador = registro.find(MetricasDeCursos.COMPLETADOS)
                .tag("via", MetricasDeCursos.VIA_EXAMEN).tag("categoria", categoria).counter();
        return contador == null ? 0 : contador.count();
    }
}
