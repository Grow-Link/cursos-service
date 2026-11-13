package com.growlink.cursos.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.domain.Curso;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PRESUPUESTO DE CONSULTAS de los dos GET que mas se piden. La regla que se protege aqui no es "pocas
// consultas" sino "un numero FIJO de consultas": el catalogo y la ruta se leen con consultas de bloque, asi que
// doblar el catalogo no puede agregar ni una consulta mas. Un N+1 que vuelva (una coleccion EAGER, un
// prerequisitosDe o un countByCursoId dentro de un bucle) rompe estas pruebas de inmediato.
//
// Se mide con las estadisticas de Hibernate (sentencias JDBC preparadas), no con suposiciones.
// Base propia para que el catalogo que se duplica sea solo el sembrado.
@SpringBootTest(properties = {"CLAUDE_API_KEY=", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cursosconsultas;MODE=PostgreSQL")
class ConsultasPorPeticionTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    // Topes con algo de holgura sobre lo que se mide hoy (4 y 7). Lo que de verdad se vigila es que el numero no
    // CREZCA con el catalogo (ver abajo); el tope solo evita que se vayan sumando consultas sueltas.
    private static final int TOPE_CATALOGO = 6;
    private static final int TOPE_ROADMAP = 10;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CursoRepository cursoRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void elCatalogoSeLeeConUnNumeroFijoDeConsultas() throws Exception {
        int cursosAntes = leer("/api/cursos", 9300L).size();
        long consultasAntes = consultasDe("/api/cursos", 9301L);

        duplicarCatalogo();

        int cursosDespues = leer("/api/cursos", 9302L).size();
        long consultasDespues = consultasDe("/api/cursos", 9303L);

        System.out.printf("[consultas] GET /api/cursos: %d cursos -> %d consultas | %d cursos -> %d consultas%n",
                cursosAntes, consultasAntes, cursosDespues, consultasDespues);

        assertThat(cursosDespues).as("el catalogo se duplico de verdad").isGreaterThanOrEqualTo(cursosAntes * 2);
        assertThat(consultasDespues).as("GET /api/cursos no puede hacer mas consultas por tener mas cursos")
                .isEqualTo(consultasAntes);
        assertThat(consultasDespues).as("GET /api/cursos").isLessThanOrEqualTo(TOPE_CATALOGO);
    }

    @Test
    void laRutaDelUsuarioSeLeeConUnNumeroFijoDeConsultas() throws Exception {
        // el usuario 5 es el de demostracion: ya tiene su roadmap sembrado (ver CursoCatalogoSeeder)
        int pasos = leer("/api/roadmap/mio?usuarioId=5", 5L).get("cursos").size();
        long consultasAntes = consultasDe("/api/roadmap/mio?usuarioId=5", 5L);

        duplicarCatalogo();

        long consultasDespues = consultasDe("/api/roadmap/mio?usuarioId=5", 5L);

        System.out.printf("[consultas] GET /api/roadmap/mio (%d pasos): %d consultas -> con el catalogo "
                + "duplicado, %d consultas%n", pasos, consultasAntes, consultasDespues);

        assertThat(pasos).isGreaterThan(0);
        assertThat(consultasDespues).as("GET /api/roadmap/mio no puede hacer mas consultas por tener mas cursos")
                .isEqualTo(consultasAntes);
        assertThat(consultasDespues).as("GET /api/roadmap/mio").isLessThanOrEqualTo(TOPE_ROADMAP);
    }

    // ------------------------------------------------------------- ayudas

    // copia cada curso activo del catalogo (misma area, nivel, habilidades y temario) para doblar su tamano: son
    // justo los cursos que mas trabajo le dan al listado y a las alternativas de la ruta
    private void duplicarCatalogo() {
        new TransactionTemplate(transactionManager).executeWithoutResult(estado -> {
            List<Curso> originales = cursoRepository.buscarCatalogo(null, null);
            List<Curso> copias = new ArrayList<>();
            for (Curso original : originales) {
                Curso copia = new Curso(original.getTitulo() + " (copia de carga)", original.getDescripcion(),
                        original.getCategoria(), original.getNivel(), original.getHabilidades(),
                        original.getLinkContenido(), original.getPublicadorUsuarioId());
                copia.definirDetalle(original.getDuracionHoras(), original.getTemario());
                copias.add(copia);
            }
            cursoRepository.saveAll(copias);
        });
    }

    private long consultasDe(String url, long usuarioId) throws Exception {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long antes = estadisticas.getPrepareStatementCount();
        leer(url, usuarioId);
        return estadisticas.getPrepareStatementCount() - antes;
    }

    private JsonNode leer(String url, long usuarioId) throws Exception {
        String cuerpo = mockMvc.perform(get(url).header("Authorization", token(usuarioId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    private static String token(long usuarioId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(String.valueOf(usuarioId))
                .claim("rol", usuarioId >= 9000L ? "USUARIO" : "PUBLICADOR").signWith(key).compact();
    }
}
