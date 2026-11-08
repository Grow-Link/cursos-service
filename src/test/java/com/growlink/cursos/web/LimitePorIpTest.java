package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// ATRIBUTO seguridad y disponibilidad: el tope por IP (ver LimitePorIpFilter). LimiteDeGeneraciones ya protege lo
// que cuesta dinero, pero por persona identificada; esto protege al servicio de quien no se identifica, para que
// una rafaga desde una sola red no se lleve los hilos y las conexiones de todos los demas.
//
// Se usa el valor de produccion (300 por minuto) a proposito: lo que se prueba es la regla que de verdad va a
// estar puesta. La IP se manda en X-Forwarded-For porque delante del servicio hay un balanceador y la IP del
// socket seria siempre la suya.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cursoslimiteip;MODE=PostgreSQL",
        "seguridad.limite-ip.maximo-por-minuto=300",
        "seguridad.limite-ip.ventana-segundos=60"
})
class LimitePorIpTest {

    private static final int MAXIMO = 300;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry registro;

    @Test
    void unaRafagaDe301PeticionesDeLaMismaIpTerminaEn429YOtraIpSigueEntrando() throws Exception {
        String abusadora = "203.0.113.7";
        String tranquila = "203.0.113.90";
        double rechazos0 = rechazos();

        // las 300 primeras pasan: el tope no estorba al uso normal
        for (int i = 1; i <= MAXIMO; i++) {
            assertThat(salud(abusadora).getStatus()).as("peticion numero " + i).isEqualTo(200);
        }

        // la 301 ya no
        MockHttpServletResponse rechazada = salud(abusadora);
        assertThat(rechazada.getStatus()).isEqualTo(429);

        // con Retry-After, para que el cliente (o el proxy) sepa cuanto esperar
        assertThat(rechazada.getHeader("Retry-After")).isNotBlank();
        assertThat(Long.parseLong(rechazada.getHeader("Retry-After"))).isBetween(1L, 60L);
        // y el mismo cuerpo de error que el resto del servicio
        JsonNode cuerpo = objectMapper.readTree(rechazada.getContentAsString(StandardCharsets.UTF_8));
        assertThat(cuerpo.get("message").asText()).contains("Demasiadas peticiones").contains("vuelve a intentarlo");
        // y queda contado, para ver desde las metricas cuanto abuso se esta frenando
        assertThat(rechazos() - rechazos0).isEqualTo(1);

        // lo importante: el tope es POR IP, no del servicio entero. Quien no tuvo nada que ver sigue entrando
        assertThat(salud(tranquila).getStatus()).as("otra IP no paga el abuso de la primera").isEqualTo(200);
    }

    @Test
    void siLleganVariosSaltosEnXForwardedForCuentaElPrimero_queEsElClienteDeVerdad() throws Exception {
        // tal como lo arma un balanceador: cliente, proxy, proxy
        String cadena = "198.51.100.23, 10.0.0.4, 10.0.0.5";
        for (int i = 1; i <= MAXIMO; i++) {
            assertThat(salud(cadena).getStatus()).as("peticion numero " + i).isEqualTo(200);
        }
        assertThat(salud(cadena).getStatus()).isEqualTo(429);

        // el mismo cliente detras de OTROS proxies sigue estando pasado del tope: lo que cuenta es el primer valor
        assertThat(salud("198.51.100.23, 10.0.0.9").getStatus()).isEqualTo(429);
        // y un proxy distinto como primer valor es otra IP, que empieza de cero
        assertThat(salud("10.0.0.4, 198.51.100.23").getStatus()).isEqualTo(200);
    }

    @Test
    void sinXForwardedForSeUsaLaIpDelSocketYLasPeticionesNormalesPasan() throws Exception {
        // sin la cabecera no hay excusa para dejar pasar todo: se cuenta la IP del socket (aqui la de la prueba)
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse salud(String xForwardedFor) throws Exception {
        return mockMvc.perform(get("/actuator/health").header("X-Forwarded-For", xForwardedFor))
                .andReturn().getResponse();
    }

    private double rechazos() {
        var contador = registro.find("growlink.http.limite-ip.rechazos").counter();
        return contador == null ? 0 : contador.count();
    }
}
