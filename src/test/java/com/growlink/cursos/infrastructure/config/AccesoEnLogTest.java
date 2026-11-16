package com.growlink.cursos.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

// ATRIBUTO observabilidad (escenario: seguir una peticion entre servicios): cada peticion deja UNA linea de log al terminar,
// con su identificador, el metodo, la ruta, el codigo y lo que tardo, aunque haya salido bien. Sin esa linea una peticion
// correcta no deja rastro que buscar por su X-Request-Id.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccesoEnLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cadaPeticionDejaUnaLineaConSuIdentificadorMetodoRutaYCodigo(CapturedOutput salida) throws Exception {
        String rid = "prueba-acceso-log-0001";

        mockMvc.perform(get("/api/cursos").header("X-Request-Id", rid)).andReturn();

        JsonNode linea = lineaDeAcceso(salida, rid);
        assertThat(linea.get("level").asText()).isEqualTo("INFO");
        assertThat(linea.get("message").asText()).startsWith("peticion GET ").containsPattern(" -> 40[13] en [0-9]+ ms$");
        assertThat(linea.get("rid").asText()).isEqualTo(rid);
        assertThat(linea.get("servicio").asText()).isEqualTo("cursos-service");
        assertThat(linea.get("instancia").asText()).isNotBlank();
    }

    @Test
    void laRutaEsElPatronQueAtendioLaPeticionYNoLoQueEscribioQuienLlamo(CapturedOutput salida) throws Exception {
        String rid = "prueba-acceso-log-0002";

        mockMvc.perform(get("/api/cursos/no-existe-esta-ruta-<script>").header("X-Request-Id", rid)).andReturn();

        String mensaje = lineaDeAcceso(salida, rid).get("message").asText();
        assertThat(mensaje).doesNotContain("no-existe-esta-ruta").doesNotContain("<script>");
    }

    @Test
    void unMetodoRaroSeAnotaComoOtro(CapturedOutput salida) throws Exception {
        String rid = "prueba-acceso-log-0003";

        try {
            mockMvc.perform(request(HttpMethod.valueOf("TRACE-ESPECIAL"), "/api/cursos").header("X-Request-Id", rid)).andReturn();
        } catch (Exception rechazada) {
            // el cortafuegos de Spring Security puede rechazar un metodo asi con una excepcion: igual tiene que quedar anotado
            assertThat(rechazada).isNotNull();
        }

        assertThat(lineaDeAcceso(salida, rid).get("message").asText()).startsWith("peticion OTRO ");
    }

    @Test
    void lasConsultasDeSaludNoSeAnotan(CapturedOutput salida) throws Exception {
        String rid = "prueba-acceso-log-0004";

        mockMvc.perform(get("/actuator/health").header("X-Request-Id", rid)).andReturn();

        assertThat(salida.getOut().lines().filter(l -> l.contains(rid) && l.contains("SolicitudIdFilter")).toList()).isEmpty();
    }

    private static JsonNode lineaDeAcceso(CapturedOutput salida, String rid) throws Exception {
        List<String> lineas = salida.getOut().lines()
                .filter(l -> l.contains(rid) && l.contains("SolicitudIdFilter")).toList();
        assertThat(lineas).as("lineas de acceso con \"" + rid + "\"").hasSize(1);
        return JSON.readTree(lineas.get(0));
    }
}
