package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO observabilidad: las medidas del roadmap cuentan lo que de verdad paso. La IA es falsa a proposito para
// poder provocar cada resultado (responde, falla, dice que la meta no es viable, inventa un curso, se pasa de horas)
// y comprobar que cada uno se cuenta una sola vez y en su medida. Cada prueba parte de un catalogo propio (un curso
// nuevo cambia la clave de la cache), asi no reutiliza lo que guardo otra.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosmetricas;MODE=PostgreSQL")
@Import(RoadmapRestriccionTest.IaFalsaConfig.class)
class MetricasDeRoadmapTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final AtomicInteger SIGUIENTE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry registro;
    @Autowired
    private RoadmapRestriccionTest.IaFalsa ia;

    @BeforeEach
    void iaPorDefecto() {
        ia.decide = catalogo -> List.of();
        ia.decideConContexto = null;
    }

    // ---------------------------------------------------------------- lo que se cuenta

    @Test
    void unaRespuestaDeLaIaSeCuentaYLaSiguienteIgualSaleDeLaCache() throws Exception {
        Long a = crearCurso("Derecho Aeronautico A");
        Long b = crearCurso("Derecho Aeronautico B");
        ia.decide = catalogo -> List.of(a, b);

        double ia0 = medida("growlink.roadmap.generaciones", "fuente", "IA");
        double fallo0 = medida("growlink.roadmap.cache", "resultado", "fallo");
        double acierto0 = medida("growlink.roadmap.cache", "resultado", "acierto");
        double respondio0 = medida("growlink.roadmap.ia.llamadas", "resultado", "respondio");
        double aceptada0 = medida("growlink.roadmap.ia.propuestas", "resultado", "aceptada");
        long duraciones0 = registro.timer("growlink.roadmap.ia.duracion").count();

        generar(9401L, 201);
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "IA") - ia0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.cache", "resultado", "fallo") - fallo0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.ia.llamadas", "resultado", "respondio") - respondio0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.ia.propuestas", "resultado", "aceptada") - aceptada0).isEqualTo(1);
        assertThat(registro.timer("growlink.roadmap.ia.duracion").count() - duraciones0).isEqualTo(1);

        generar(9402L, 201); // otra persona, la misma entrada: sale de la cache y no vuelve a llamar a la IA
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "IA") - ia0).isEqualTo(2);
        assertThat(medida("growlink.roadmap.cache", "resultado", "acierto") - acierto0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.ia.llamadas", "resultado", "respondio") - respondio0).as("sin otra llamada").isEqualTo(1);
    }

    @Test
    void siLaIaFallaSeCuentaElFalloYElRoadmapSaleDelRespaldo() throws Exception {
        crearCurso("Derecho Antartico A");
        ia.decide = catalogo -> {
            throw new RoadmapAiException("simulacro: Claude no contesto");
        };
        double fallo0 = medida("growlink.roadmap.ia.llamadas", "resultado", "fallo");
        double respaldo0 = medida("growlink.roadmap.generaciones", "fuente", "RESPALDO");

        generar(9403L, 201);

        assertThat(medida("growlink.roadmap.ia.llamadas", "resultado", "fallo") - fallo0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "RESPALDO") - respaldo0).isEqualTo(1);
    }

    @Test
    void unaPropuestaInventadaSeCuentaComoInvalida() throws Exception {
        crearCurso("Derecho Artico A");
        ia.decide = catalogo -> List.of(999_999L);
        double invalida0 = medida("growlink.roadmap.ia.propuestas", "resultado", "invalida");
        double respaldo0 = medida("growlink.roadmap.generaciones", "fuente", "RESPALDO");

        generar(9404L, 201);

        assertThat(medida("growlink.roadmap.ia.propuestas", "resultado", "invalida") - invalida0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "RESPALDO") - respaldo0).isEqualTo(1);
    }

    @Test
    void siLaIaDiceQueLaMetaNoEsViableSeCuentaYNoSeGeneraNada() throws Exception {
        crearCurso("Derecho Austral A");
        ia.decide = catalogo -> {
            throw new MetaNoViableException("simulacro: esa meta no tiene que ver con el catalogo");
        };
        double noViable0 = medida("growlink.roadmap.ia.llamadas", "resultado", "no_viable");
        double ia0 = medida("growlink.roadmap.generaciones", "fuente", "IA");
        double respaldo0 = medida("growlink.roadmap.generaciones", "fuente", "RESPALDO");

        generar(9405L, 422);

        assertThat(medida("growlink.roadmap.ia.llamadas", "resultado", "no_viable") - noViable0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "IA") - ia0).isZero();
        assertThat(medida("growlink.roadmap.generaciones", "fuente", "RESPALDO") - respaldo0).isZero();
    }

    @Test
    void siLaIaSePasaDelTiempoSeCuentaElExcesoYLaCorreccion() throws Exception {
        Long uno = crearCurso("Derecho Alpino A");
        crearCurso("Derecho Alpino B");
        crearCurso("Derecho Alpino C");
        // la primera vez se pasa (4 cursos de 20 h contra 30 h); con la correccion devuelve uno solo
        ia.decideConContexto = (catalogo, contexto) -> contexto.correccion() == null
                ? catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO")).map(CursoGrafoNodo::cursoId).limit(4).toList()
                : List.of(uno);
        double exceso0 = medida("growlink.roadmap.ia.propuestas", "resultado", "excede_horas");
        double corregida0 = medida("growlink.roadmap.ia.propuestas", "resultado", "corregida");
        double respondio0 = medida("growlink.roadmap.ia.llamadas", "resultado", "respondio");

        generarConTiempo(9406L, 2, 15);

        assertThat(medida("growlink.roadmap.ia.propuestas", "resultado", "excede_horas") - exceso0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.ia.propuestas", "resultado", "corregida") - corregida0).isEqualTo(1);
        assertThat(medida("growlink.roadmap.ia.llamadas", "resultado", "respondio") - respondio0).as("la normal y la de correccion").isEqualTo(2);
    }

    // ---------------------------------------------------------------- quien puede verlas

    @Test
    void lasMetricasSoloLasVeUnAdmin() throws Exception {
        Long a = crearCurso("Derecho Andino A");
        ia.decide = catalogo -> List.of(a);
        generar(9407L, 201); // asi existe al menos una medida del roadmap

        // sin token y con token de una persona comun: no
        assertThat(mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/actuator/metrics").header("Authorization", token(9407L, "USUARIO")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/actuator/metrics/growlink.roadmap.generaciones").header("Authorization", token(9407L, "PUBLICADOR")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        // un ADMIN si: ve la lista con las medidas de GrowLink y el detalle de una
        String lista = mockMvc.perform(get("/actuator/metrics").header("Authorization", token(3L, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(lista).contains("growlink.roadmap.generaciones", "growlink.roadmap.cache", "growlink.roadmap.ia.llamadas");

        JsonNode detalle = objectMapper.readTree(mockMvc.perform(get("/actuator/metrics/growlink.roadmap.generaciones")
                        .param("tag", "fuente:IA").header("Authorization", token(3L, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(detalle.get("measurements").get(0).get("value").asDouble()).isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- ayudas

    // el valor actual de un contador con esa etiqueta; 0 si todavia no existe (se crea al contar por primera vez)
    private double medida(String nombre, String etiqueta, String valor) {
        var contador = registro.find(nombre).tag(etiqueta, valor).counter();
        return contador == null ? 0 : contador.count();
    }

    private void generar(long usuarioId, int estadoEsperado) throws Exception {
        pedir(cuerpo(usuarioId), usuarioId, estadoEsperado);
    }

    private void generarConTiempo(long usuarioId, int horasPorSemana, int semanas) throws Exception {
        Map<String, Object> cuerpo = cuerpo(usuarioId);
        cuerpo.put("horasPorSemana", horasPorSemana);
        cuerpo.put("semanas", semanas);
        pedir(cuerpo, usuarioId, 201);
    }

    private Map<String, Object> cuerpo(long usuarioId) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", "Quiero trabajar en derecho");
        cuerpo.put("intereses", List.of("DERECHO"));
        cuerpo.put("nivel", "PRINCIPIANTE");
        return cuerpo;
    }

    private void pedir(Map<String, Object> cuerpo, long usuarioId, int estadoEsperado) throws Exception {
        mockMvc.perform(post("/api/roadmap/generar").header("Authorization", token(usuarioId, "USUARIO"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado));
    }

    private Long crearCurso(String titulo) throws Exception {
        long publicador = 9400L + SIGUIENTE.incrementAndGet();
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicador);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", 20);
        String respuesta = mockMvc.perform(post("/api/cursos").header("Authorization", token(publicador, "PUBLICADOR"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private static String token(long usuarioId, String rol) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId)).claim("rol", rol).signWith(key).compact();
    }
}
