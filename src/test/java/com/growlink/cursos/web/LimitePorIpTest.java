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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// ATRIBUTO seguridad y disponibilidad: el tope por IP (ver LimitePorIpFilter). LimiteDeGeneraciones ya protege lo
// que cuesta dinero, pero por persona identificada; esto protege al servicio de quien no se identifica, para que
// una rafaga desde una sola red no se lleve los hilos y las conexiones de todos los demas.
//
// Se usa el valor de produccion (300 por minuto) a proposito: lo que se prueba es la regla que de verdad va a
// estar puesta.
//
// La IP se pone con setRemoteAddr y NO en X-Forwarded-For, porque es ahi donde la deja el RemoteIpValve de Tomcat
// (server.forward-headers-strategy=native) despues de leerle la cabecera al proxy de confianza. Por eso las IPs
// llevan puerto, "203.0.113.7:51234": es la forma exacta en que el valve copia lo que manda Azure App Service,
// con el puerto de la conexion del cliente. MockMvc no levanta Tomcat, asi que el valve no corre aqui; lo que se
// simula es su resultado.
//
// Cada prueba usa una IP base DISTINTA: comparten la ventana de 60 s y el contador del filtro, asi que dos
// pruebas con la misma base se pisarian entre si.
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
    void elPuertoQueAgregaAzureNoConvierteCadaConexionEnUnaIpNueva() throws Exception {
        // asi queda en getRemoteAddr() detras de Azure App Service: IP:puerto, y el puerto cambia con cada
        // conexion del cliente. Si contara el puerto, cada conexion seria otra "IP" que empieza de cero y el tope
        // no frenaria nunca a nadie: es justo el error que tenia el filtro en este camino
        String ip = "198.51.100.23";
        for (int i = 1; i <= MAXIMO; i++) {
            assertThat(salud(ip + ":" + (40000 + i)).getStatus()).as("peticion numero " + i).isEqualTo(200);
        }

        // la 301, desde un puerto que no se habia usado, es la misma IP: 429
        assertThat(salud(ip + ":50000").getStatus()).isEqualTo(429);
        // y sin puerto (IPv4 pelada, como la deja nginx en el stack local) tambien es el mismo cliente
        assertThat(salud(ip).getStatus()).isEqualTo(429);
    }

    @Test
    void unXForwardedForInventadoYaNoCambiaLaClave() throws Exception {
        // Cuando la clave salia del primer valor de X-Forwarded-For, cualquiera se saltaba el tope mandando un
        // valor distinto en cada peticion. Ahora la cabecera no se mira: la clave es la IP del socket, que detras
        // de un proxy de confianza es la que puso el proxy
        String ip = "192.0.2.44";
        for (int i = 1; i <= MAXIMO; i++) {
            assertThat(salud(ip + ":" + (40000 + i)).getStatus()).as("peticion numero " + i).isEqualTo(200);
        }

        // ya esta pasada del tope: inventarse una cabecera no da un contador nuevo
        assertThat(salud(ip + ":50001", "1.2.3.4").getStatus()).isEqualTo(429);
        assertThat(salud(ip + ":50002", "5.6.7.8").getStatus()).isEqualTo(429);
        assertThat(salud(ip + ":50003", "1.2.3.4, 10.0.0.9").getStatus()).isEqualTo(429);

        // y al reves: la cabecera tampoco sirve para que a otro le cobren el abuso ajeno
        assertThat(salud("192.0.2.99", ip).getStatus())
                .as("otra IP no se bloquea por mandar la IP de la abusadora en la cabecera").isEqualTo(200);
    }

    @Test
    void conIpv6EntreCorchetesPasaLoMismo() throws Exception {
        // la IPv6 con puerto va entre corchetes, que es como la deja el valve
        String ip = "2001:db8::44";
        for (int i = 1; i <= MAXIMO; i++) {
            assertThat(salud("[" + ip + "]:" + (40000 + i)).getStatus()).as("peticion numero " + i).isEqualTo(200);
        }

        assertThat(salud("[" + ip + "]:50000").getStatus()).isEqualTo(429);
        // entre corchetes sin puerto, y sin corchetes, es el mismo cliente
        assertThat(salud("[" + ip + "]").getStatus()).isEqualTo(429);
        assertThat(salud(ip).getStatus()).isEqualTo(429);
    }

    @Test
    void lasPeticionesNormalesPasan() throws Exception {
        // sin tocar nada (la IP del socket que pone MockMvc) una peticion suelta entra sin estorbo
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse salud(String ipDelSocket) throws Exception {
        return mockMvc.perform(get("/actuator/health").with(desde(ipDelSocket)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse salud(String ipDelSocket, String xForwardedForInventado) throws Exception {
        return mockMvc.perform(get("/actuator/health")
                        .with(desde(ipDelSocket))
                        .header("X-Forwarded-For", xForwardedForInventado))
                .andReturn().getResponse();
    }

    // la IP del socket, que es donde el RemoteIpValve deja la del cliente antes de que corran los filtros
    private static RequestPostProcessor desde(String ipDelSocket) {
        return peticion -> {
            peticion.setRemoteAddr(ipDelSocket);
            return peticion;
        };
    }

    private double rechazos() {
        var contador = registro.find("growlink.http.limite-ip.rechazos").counter();
        return contador == null ? 0 : contador.count();
    }
}
