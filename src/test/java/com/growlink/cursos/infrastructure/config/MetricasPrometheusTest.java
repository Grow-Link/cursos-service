package com.growlink.cursos.infrastructure.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// ATRIBUTO seguridad y observabilidad: /actuator/prometheus le da las metricas a Prometheus, y solo a Prometheus
// (ver TokenDeMetricas). Con el registro de Prometheus de verdad (@AutoConfigureObservability: sin esto, las pruebas
// de Spring Boot apagan la exportacion de metricas) y la cadena de seguridad de verdad, en modo legacy.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursosprometheus;MODE=PostgreSQL",
        "seguridad.limite-ip.maximo-por-minuto=0",
        "observabilidad.metricas-token=" + MetricasPrometheusTest.TOKEN,
        "observabilidad.instancia=replica-de-prueba-1"
})
class MetricasPrometheusTest {

    static final String TOKEN = "token-del-scrape-de-pruebas";
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void conElTokenDelScrapeSaleTodoEnFormatoDePrometheusConSuInstancia() throws Exception {
        // una peticion cualquiera, para que haya latencias que mostrar
        mockMvc.perform(get("/actuator/health"));

        MockHttpServletResponse respuesta = metricas(TOKEN);

        assertThat(respuesta.getStatus()).isEqualTo(200);
        String cuerpo = respuesta.getContentAsString(StandardCharsets.UTF_8);
        // las de negocio de este servicio y el estado del cortacircuito de la IA...
        assertThat(cuerpo).contains("growlink_cursos_completados_total{")
                .contains("growlink_roadmap_ia_cortacircuito_estado{");
        // ...todas con la replica que las midio
        assertThat(cuerpo).contains("instancia=\"replica-de-prueba-1\"");
        // y las cubetas de latencia, que es de donde el tablero saca el p95 por replica
        assertThat(cuerpo).contains("http_server_requests_seconds_bucket{").contains("le=\"0.1\"");
    }

    @Test
    void sinTokenOConUnoEquivocadoNoSeVen() throws Exception {
        // en modo legacy una peticion sin credenciales es 403 (ver SecurityConfig), y esta no es la excepcion
        assertThat(mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(metricas("token-que-no-es-el-del-scrape").getStatus()).isEqualTo(403);
        assertThat(metricas(TOKEN.substring(0, TOKEN.length() - 1)).getStatus()).isEqualTo(403);
    }

    @Test
    void elTokenDeUnaPersonaNoAbreLasMetricasNiSiendoAdmin() throws Exception {
        // un ADMIN tiene /actuator/metrics, pero /actuator/prometheus es solo para el scrape
        assertThat(mockMvc.perform(get("/actuator/metrics").header("Authorization", tokenDe(1, "ADMIN")))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/actuator/prometheus").header("Authorization", tokenDe(1, "ADMIN")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void lasVariantesDeLaRutaTampocoLasAbren() throws Exception {
        // con una barra al final, con un parametro de ruta o con mayusculas no hay otra puerta: nunca 200
        for (String ruta : new String[]{"/actuator/prometheus/", "/actuator/prometheus;x=1", "/actuator/Prometheus"}) {
            assertThat(mockMvc.perform(get(ruta)).andReturn().getResponse().getStatus())
                    .as("sin token: " + ruta).isNotEqualTo(200);
            assertThat(mockMvc.perform(get(ruta).header("Authorization", tokenDe(1, "ADMIN")))
                    .andReturn().getResponse().getStatus())
                    .as("con el token de un ADMIN: " + ruta).isNotEqualTo(200);
        }
    }

    @Test
    void laSaludSigueAbiertaParaElBalanceador() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private MockHttpServletResponse metricas(String token) throws Exception {
        return mockMvc.perform(get("/actuator/prometheus").header(TokenDeMetricas.CABECERA, token))
                .andReturn().getResponse();
    }

    private static String tokenDe(long usuarioId, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(String.valueOf(usuarioId))
                .claim("roles", List.of(roles))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
