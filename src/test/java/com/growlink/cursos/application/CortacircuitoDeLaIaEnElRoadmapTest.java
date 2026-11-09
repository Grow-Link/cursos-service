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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO disponibilidad (tolerancia a fallos), visto desde la peticion de la persona: cuando la IA falla, el
// servicio no se cae NI se queda esperandola. CortacircuitoDeLaIaTest prueba las reglas del cortacircuito solo;
// esto prueba lo que la persona recibe: siempre un roadmap valido (201), con generadoPor=RESPALDO para no decir
// que lo armo la IA cuando no fue asi, y rapido, porque con el circuito abierto ya no se sale a internet.
//
// La IA es falsa a proposito (la misma de RoadmapRestriccionTest): es la unica forma de provocar un error, una
// respuesta que no sirve o una falla inesperada sin depender de que Claude se caiga de verdad.
// El tope por persona se apaga (generaciones: 0) porque aqui se piden varios roadmaps seguidos a proposito y lo
// que se esta probando es el cortacircuito, no ese otro tope (ver CalidadDeServicioTest).
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursoscortacircuito;MODE=PostgreSQL",
        "roadmap.limite.generaciones=0",
        "roadmap.ia.cortacircuito.fallos=3",
        "roadmap.ia.cortacircuito.espera-segundos=60"
})
@Import(RoadmapRestriccionTest.IaFalsaConfig.class)
class CortacircuitoDeLaIaEnElRoadmapTest {

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
    @Autowired
    private CortacircuitoDeLaIa cortacircuito;

    @BeforeEach
    void desdeCero() {
        ia.decide = catalogo -> List.of();
        ia.decideConContexto = null;
        ia.llamadas = 0;
        // el cortacircuito es uno por replica y vive mientras viva el contexto: cada prueba empieza con el cerrado
        cortacircuito.reiniciar();
    }

    @Test
    void tresFallosSeguidosDeLaIaAbrenElCortacircuitoYLaCuartaPeticionNiLaLlama() throws Exception {
        crearCurso("Derecho Marciano A");
        ia.decide = catalogo -> {
            throw new RoadmapAiException("simulacro: Claude no contesto");
        };
        double rechazos0 = contador("growlink.roadmap.ia.cortacircuito.rechazos");

        // los tres primeros si la llaman (y ya responden con el respaldo, sin 5xx)
        for (long usuario : List.of(9501L, 9502L, 9503L)) {
            assertThat(generar(usuario).get("generadoPor").asText()).isEqualTo("RESPALDO");
        }
        assertThat(ia.llamadas).as("se intento tres veces antes de rendirse").isEqualTo(3);
        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");

        // de aqui en adelante ya no se la llama: el roadmap lo arma el respaldo directamente
        long inicio = System.nanoTime();
        JsonNode cuarto = generar(9504L);
        long milisegundos = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ia.llamadas).as("con el circuito abierto no se llama a la IA").isEqualTo(3);
        assertThat(cuarto.get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(cuarto.get("cursos")).isNotEmpty();
        assertThat(milisegundos).as("el respaldo contesta sin salir a internet").isLessThan(2000L);
        assertThat(contador("growlink.roadmap.ia.cortacircuito.rechazos") - rechazos0).isEqualTo(1);
        assertThat(estadoEnLasMetricas()).as("y queda a la vista en las metricas").isEqualTo(2);
    }

    @Test
    void unaRespuestaQueNoSirveCuentaComoFalloYTambienAbreElCortacircuito() throws Exception {
        crearCurso("Derecho Lunar A");
        // la IA contesta sin error, pero recomienda un curso que no existe: no se le puede hacer caso
        ia.decide = catalogo -> List.of(999_999L);

        for (long usuario : List.of(9511L, 9512L, 9513L)) {
            assertThat(generar(usuario).get("generadoPor").asText()).isEqualTo("RESPALDO");
        }

        assertThat(cortacircuito.estado()).as("tres respuestas que no sirven son tres fallos").isEqualTo("ABIERTO");
        assertThat(generar(9514L).get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(ia.llamadas).isEqualTo(3);
    }

    @Test
    void unaFallaInesperadaDeLaIaTampocoSaleComoErrorDelServidor() throws Exception {
        crearCurso("Derecho Solar A");
        // no una RoadmapAiException, sino cualquier otra cosa: un cliente HTTP que revienta, un nulo...
        ia.decide = catalogo -> {
            throw new IllegalStateException("simulacro: el cliente de Claude revento");
        };

        JsonNode roadmap = generar(9521L); // 201, nunca 5xx

        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(roadmap.get("cursos")).isNotEmpty();
    }

    @Test
    void conLaIaRespondiendoBienElCortacircuitoNoEstorba() throws Exception {
        Long curso = crearCurso("Derecho Estelar A");
        ia.decide = catalogo -> List.of(curso);

        JsonNode roadmap = generar(9531L);

        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("IA");
        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");
        assertThat(estadoEnLasMetricas()).isZero();
    }

    @Test
    void despuesDeAbrirse_unaRespuestaBuenaDeLaPeticionDePruebaDevuelveLaIa() throws Exception {
        Long curso = crearCurso("Derecho Orbital A");
        ia.decide = catalogo -> {
            throw new RoadmapAiException("simulacro: Claude no contesto");
        };
        generar(9541L);
        generar(9542L);
        generar(9543L);
        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");

        // la IA vuelve. Se adelanta el reloj del cortacircuito a mano (reiniciar) porque esperar los 60 s de
        // produccion no prueba nada que CortacircuitoDeLaIaTest no pruebe ya con tiempos cortos
        ia.decide = catalogo -> List.of(curso);
        cortacircuito.reiniciar();

        JsonNode roadmap = generar(9544L);

        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("IA");
        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");
    }

    // ---------------------------------------------------------------- ayudas

    private double contador(String nombre) {
        var contador = registro.find(nombre).counter();
        return contador == null ? 0 : contador.count();
    }

    private double estadoEnLasMetricas() {
        var medida = registro.find("growlink.roadmap.ia.cortacircuito.estado").gauge();
        assertThat(medida).as("la medida del estado tiene que existir").isNotNull();
        return medida.value();
    }

    private JsonNode generar(long usuarioId) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", "Quiero trabajar en derecho");
        cuerpo.put("intereses", List.of("DERECHO"));
        cuerpo.put("nivel", "PRINCIPIANTE");
        String respuesta = mockMvc.perform(post("/api/roadmap/generar")
                        .header("Authorization", token(usuarioId, "USUARIO"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta);
    }

    private Long crearCurso(String titulo) throws Exception {
        long publicador = 9590L + SIGUIENTE.incrementAndGet();
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("titulo", titulo);
        cuerpo.put("categoria", "DERECHO");
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("habilidadIds", List.of());
        cuerpo.put("publicadorUsuarioId", publicador);
        cuerpo.put("prerequisitoIds", List.of());
        cuerpo.put("duracionHoras", 20);
        String respuesta = mockMvc.perform(post("/api/cursos")
                        .header("Authorization", token(publicador, "PUBLICADOR"))
                        .contentType("application/json").content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta).get("id").asLong();
    }

    private static String token(long usuarioId, String rol) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId)).claim("rol", rol).signWith(key).compact();
    }
}
