package com.growlink.cursos.web;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ATRIBUTO disponibilidad: una replica que pierde la base de datos deja de estar LISTA (readiness DOWN, 503), y asi el
// balanceador y el health check de Azure dejan de mandarle trafico; pero sigue VIVA (liveness UP), porque reiniciarla
// no arregla la base y un reinicio de todas las replicas a la vez solo empeoraria la caida.
//
// Por defecto Spring Boot NO mete la base en readiness: hay que pedirlo
// (management.endpoint.health.group.readiness.include en application.yml). Sin eso, esta prueba falla.
//
// Para perder la base de verdad se cierra el pool de conexiones: desde ahi nadie consigue una conexion, que es lo que
// ve la replica cuando la base se cae o se corta la red. Contexto propio, que se descarta al terminar.
@SpringBootTest(properties = "CLAUDE_API_KEY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosreadiness;MODE=PostgreSQL")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessConLaBaseDeDatosTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    @Test
    void sinBaseDeDatosLaReplicaDejaDeEstarListaPeroSigueViva() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        dataSource.unwrap(HikariDataSource.class).close();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                // sigue sin contar el detalle: ni que fue la base, ni cual
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
