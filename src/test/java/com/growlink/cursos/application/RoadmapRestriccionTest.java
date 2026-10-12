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
@SpringBootTest(properties = "CLAUDE_API_KEY=")
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
        // si se define, decide mirando tambien el contexto (por ejemplo, si trae una correccion de tiempo)
        volatile java.util.function.BiFunction<List<CursoGrafoNodo>, ContextoRoadmap, List<Long>> decideConContexto = null;
        volatile List<Long> ultimoCatalogoVisto = List.of();
        volatile int llamadas = 0;

        IaFalsa() {
            super("llave-falsa", "modelo-falso", 0.0, new ObjectMapper());
        }

        @Override
        public PropuestaRoadmap generarRuta(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
            ultimoCatalogoVisto = catalogoActivo.stream().map(CursoGrafoNodo::cursoId).toList();
            llamadas++;
            List<Long> ids = decideConContexto != null ? decideConContexto.apply(catalogoActivo, contexto) : decide.apply(catalogoActivo);
            List<CursoElegido> cursos = ids.stream()
                    .map(id -> new CursoElegido(id, "razon de prueba")).toList();
            return new PropuestaRoadmap(cursos, "resumen de prueba");
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
        ia.decideConContexto = null;
        ia.llamadas = 0;
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

        // lo importante: el curso dado de baja no entra. (El respaldo elige segun la meta y con un maximo de
        // cursos, y otras pruebas de esta clase dejan varios cursos de Derecho, por eso no se exige uno puntual)
        assertThat(idsDelRoadmap(roadmap)).isNotEmpty().doesNotContain(retirado);
        assertThat(vigente).isNotNull();
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
    }

    @Test
    void siLaIaInventaUnCursoSeRechazaYSeUsaElRespaldo() throws Exception {
        Long vigente = crearCurso("Derecho Laboral I", 7003L);
        ia.decide = catalogo -> List.of(999_999L);

        JsonNode roadmap = generar(7102L, 7102L, 201);

        // el respaldo arma el roadmap con cursos reales del catalogo, el id inventado no entra
        assertThat(idsDelRoadmap(roadmap)).isNotEmpty().doesNotContain(999_999L);
        assertThat(vigente).isNotNull();
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

    // ---------------------------------------------------------- el tiempo disponible

    @Test
    void siLaIaSePasaDelTiempoSeLeCorrigeUnaVezYSiNoCorrigeSeUsaElRespaldo() throws Exception {
        crearCurso("Derecho de Familia I", 7010L);
        crearCurso("Derecho de Familia II", 7010L);
        crearCurso("Derecho de Familia III", 7010L);

        // una IA terca: siempre devuelve 4 cursos de Derecho (80 h), sin importar el tope (cada curso son 20 h)
        ia.decide = catalogo -> catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                .map(CursoGrafoNodo::cursoId).limit(4).toList();

        JsonNode roadmap = generarConTiempo(7110L, 2, 15, 201); // 2 h x 15 semanas = 30 h

        assertThat(ia.llamadas).as("una llamada normal y una sola de correccion").isEqualTo(2);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("RESPALDO");
        assertThat(roadmap.get("horasTotales").asInt()).isLessThanOrEqualTo(30).isGreaterThan(0);
        assertThat(roadmap.get("presupuestoHoras").asInt()).isEqualTo(30);
    }

    @Test
    void siLaIaCorrigeElTiempoSeQuedaConSuRutaYSeMarcaComoIa() throws Exception {
        Long uno = crearCurso("Derecho Minero I", 7011L);
        crearCurso("Derecho Minero II", 7011L);
        crearCurso("Derecho Minero III", 7011L);

        // la primera vez se pasa; cuando le avisan del tope, devuelve un solo curso (20 h)
        ia.decideConContexto = (catalogo, contexto) -> contexto.correccion() == null
                ? catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO")).map(CursoGrafoNodo::cursoId).limit(4).toList()
                : List.of(uno);

        JsonNode roadmap = generarConTiempo(7111L, 2, 15, 201);

        assertThat(ia.llamadas).isEqualTo(2);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("IA");
        assertThat(idsDelRoadmap(roadmap)).containsExactly(uno);
        assertThat(roadmap.get("horasTotales").asInt()).isEqualTo(20);
    }

    @Test
    void siLaIaYaRespetaElTiempoNoSeLeVuelveAPreguntar() throws Exception {
        Long uno = crearCurso("Derecho Aduanero I", 7012L);
        ia.decide = catalogo -> List.of(uno);

        JsonNode roadmap = generarConTiempo(7112L, 5, 8, 201); // 40 h, sobra

        assertThat(ia.llamadas).isEqualTo(1);
        assertThat(roadmap.get("generadoPor").asText()).isEqualTo("IA");
    }

    // ---------------------------------------------------------- determinismo: la misma entrada, la misma ruta

    @Test
    void laMismaEntradaNoVuelveAPreguntarleALaIaYDaLaMismaRuta() throws Exception {
        crearCurso("Derecho Marítimo I", 7020L);
        crearCurso("Derecho Marítimo II", 7020L);
        crearCurso("Derecho Marítimo III", 7020L);
        // una IA "caprichosa": cada vez que se le pregunta contesta una ruta distinta
        java.util.concurrent.atomic.AtomicInteger vez = new java.util.concurrent.atomic.AtomicInteger();
        ia.decide = catalogo -> {
            List<Long> derecho = catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                    .map(CursoGrafoNodo::cursoId).sorted().toList();
            return List.of(derecho.get(vez.getAndIncrement() % 2 == 0 ? derecho.size() - 1 : 0));
        };

        JsonNode primero = generar(7120L, 7120L, 201);
        JsonNode segundo = generar(7121L, 7121L, 201); // otra persona, exactamente la misma entrada
        JsonNode tercero = generar(7122L, 7122L, 201);

        assertThat(ia.llamadas).as("la IA se consulta una sola vez").isEqualTo(1);
        assertThat(idsDelRoadmap(segundo)).containsExactlyElementsOf(idsDelRoadmap(primero));
        assertThat(idsDelRoadmap(tercero)).containsExactlyElementsOf(idsDelRoadmap(primero));
        assertThat(segundo.get("generadoPor").asText()).isEqualTo("IA");
        assertThat(segundo.get("cursos").get(0).get("razon").asText()).isEqualTo("razon de prueba");
    }

    @Test
    void siElCatalogoCambiaSeLeVuelveAPreguntarALaIa() throws Exception {
        crearCurso("Derecho Agrario I", 7021L);
        ia.decide = catalogo -> catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                .map(CursoGrafoNodo::cursoId).limit(2).toList();

        generar(7123L, 7123L, 201);
        assertThat(ia.llamadas).isEqualTo(1);

        crearCurso("Derecho Agrario II", 7021L); // un curso nuevo cambia el catalogo, y con eso la clave
        generar(7124L, 7124L, 201);
        assertThat(ia.llamadas).as("catalogo distinto: se pregunta de nuevo").isEqualTo(2);
    }

    @Test
    void loQueSaleDelRespaldoNoSeGuardaComoSiFueraDeLaIa() throws Exception {
        crearCurso("Derecho Notarial I", 7022L);
        ia.decide = catalogo -> {
            throw new RoadmapAiException("simulacro: Claude no contesto");
        };
        assertThat(generar(7125L, 7125L, 201).get("generadoPor").asText()).isEqualTo("RESPALDO");

        // la IA se recupera: la misma entrada ahora SI la responde la IA (no se queda pegada la respuesta de emergencia)
        ia.decide = catalogo -> catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO"))
                .map(CursoGrafoNodo::cursoId).limit(1).toList();
        assertThat(generar(7126L, 7126L, 201).get("generadoPor").asText()).isEqualTo("IA");
    }

    @Test
    void dosPeticionesIgualesALaVezLeCuestanUnaSolaLlamadaALaIa() throws Exception {
        crearCurso("Derecho Portuario I", 7030L);
        crearCurso("Derecho Portuario II", 7030L);
        // una IA lenta: mientras contesta, las otras peticiones iguales tienen que esperar y reutilizar su respuesta
        ia.decide = catalogo -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return catalogo.stream().filter(c -> c.categoria().name().equals("DERECHO")).map(CursoGrafoNodo::cursoId).limit(2).toList();
        };

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<JsonNode>> trabajos = new java.util.ArrayList<>();
            for (long usuario = 7140L; usuario < 7145L; usuario++) {
                long u = usuario;
                trabajos.add(pool.submit(() -> generar(u, u, 201)));
            }
            List<Long> primera = null;
            for (var trabajo : trabajos) {
                List<Long> ids = idsDelRoadmap(trabajo.get(30, java.util.concurrent.TimeUnit.SECONDS));
                if (primera == null) {
                    primera = ids;
                }
                assertThat(ids).containsExactlyElementsOf(primera);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ia.llamadas).as("cinco peticiones iguales, una sola llamada a la IA").isEqualTo(1);
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

    private JsonNode generarConTiempo(long usuarioId, int horasPorSemana, int semanas, int estadoEsperado) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("usuarioId", usuarioId);
        cuerpo.put("metas", "Quiero trabajar en derecho");
        cuerpo.put("intereses", List.of("DERECHO"));
        cuerpo.put("nivel", "PRINCIPIANTE");
        cuerpo.put("horasPorSemana", horasPorSemana);
        cuerpo.put("semanas", semanas);
        String respuesta = mockMvc.perform(post("/api/roadmap/generar")
                        .header("Authorization", token(usuarioId, "STUDENT"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(cuerpo)))
                .andExpect(status().is(estadoEsperado))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(respuesta);
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
        cuerpo.put("duracionHoras", 20);

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
