package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Integracion real con la API de Claude. Este bean solo se activa si
// CLAUDE_API_KEY esta configurada, si no, RoadmapService usa
// FallbackTopologicoRoadmapAiClient. Conectar la IA real es, literalmente,
// poner la variable de entorno: Spring detecta este bean solo (ver
// @ConditionalOnExpression) y RoadmapService lo prefiere automaticamente.
@Component
@ConditionalOnExpression("'${CLAUDE_API_KEY:}' != ''")
public class ClaudeRoadmapAiClient implements RoadmapAiClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[^\\]]*]");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public ClaudeRoadmapAiClient(@Value("${CLAUDE_API_KEY:}") String apiKey,
                                  @Value("${roadmap.ai.model:claude-sonnet-5-5}") String model,
                                  ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        // sin tiempos limite, si Claude se queda pensando la peticion del usuario se quedaria colgada
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public List<Long> generarOrden(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
        String catalogoJson = aJson(catalogoActivo, "No se pudo serializar el catalogo para la IA");
        String prompt = """
                Eres el motor de recomendacion de rutas de aprendizaje de GrowLink.
                Catalogo de cursos activos disponibles (unica fuente valida, no inventes cursos ni prerequisitos):
                %s

                Datos del usuario:
                - metas: %s
                - intereses (categorias): %s
                - nivel: %s

                Elige un subconjunto de los cursoId del catalogo que forme una ruta de
                aprendizaje coherente para este usuario, respetando los prerequisitoIds
                reales de cada curso (un curso no puede ir antes que sus prerequisitos
                si estos tambien estan en la ruta).

                Responde UNICAMENTE con un arreglo JSON de cursoId en el orden sugerido,
                por ejemplo: [3,1,7]. Sin texto adicional.
                """.formatted(catalogoJson, contexto.metas(), contexto.intereses(), contexto.nivel());

        return extraerListaIds(llamarClaude(prompt, "Fallo al generar el roadmap con la API de Claude"));
    }

    @Override
    public List<Long> sugerirPrerequisitos(List<CursoGrafoNodo> candidatos, SugerenciaPrerequisitosContexto contexto) {
        String candidatosJson = aJson(candidatos, "No se pudo serializar los candidatos para la IA");
        String prompt = """
                Eres el motor de recomendacion de prerequisitos de GrowLink.
                Se esta publicando un curso nuevo:
                - titulo: %s
                - descripcion: %s
                - categoria: %s
                - nivel: %s

                Cursos activos existentes de esa misma categoria (unica fuente valida,
                no inventes cursos que no esten en esta lista):
                %s

                Elige cuales de esos cursos tienen sentido como prerequisito del curso
                nuevo (tipicamente de nivel igual o menor, y tematicamente relacionados).
                Pueden ser ninguno si no aplica.

                Responde UNICAMENTE con un arreglo JSON de cursoId, por ejemplo: [3,1].
                Sin texto adicional.
                """.formatted(contexto.titulo(), contexto.descripcion(), contexto.categoria(), contexto.nivel(),
                candidatosJson);

        return extraerListaIds(llamarClaude(prompt, "Fallo al sugerir prerequisitos con la API de Claude"));
    }

    private String aJson(Object valor, String mensajeError) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (Exception e) {
            throw new RoadmapAiException(mensajeError, e);
        }
    }

    private String llamarClaude(String prompt, String mensajeErrorHttp) {
        try {
            // los modelos nuevos piensan antes de responder y ese pensamiento cuenta dentro de
            // max_tokens, con un limite chico se quedaban sin espacio para la respuesta
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 8000,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
            );

            return restClient.post()
                    .uri(API_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RoadmapAiException e) {
            throw e;
        } catch (Exception e) {
            throw new RoadmapAiException(mensajeErrorHttp, e);
        }
    }

    List<Long> extraerListaIds(String respuestaCruda) {
        try {
            JsonNode raiz = objectMapper.readTree(respuestaCruda);
            JsonNode contenido = raiz.path("content");
            if (!contenido.isArray() || contenido.isEmpty()) {
                throw new RoadmapAiException("Respuesta de Claude sin contenido de texto");
            }
            // la respuesta puede traer bloques de "thinking" antes del texto, el primer bloque
            // no es necesariamente el que tiene la respuesta, hay que juntar solo los de tipo text
            StringBuilder textoCompleto = new StringBuilder();
            for (JsonNode bloque : contenido) {
                if ("text".equals(bloque.path("type").asText())) {
                    textoCompleto.append(bloque.path("text").asText(""));
                }
            }
            String texto = textoCompleto.toString();
            Matcher matcher = JSON_ARRAY.matcher(texto);
            if (!matcher.find()) {
                throw new RoadmapAiException("La respuesta de Claude no trae un arreglo JSON de cursoId");
            }
            JsonNode arreglo = objectMapper.readTree(matcher.group());
            return java.util.stream.StreamSupport.stream(arreglo.spliterator(), false)
                    .map(JsonNode::asLong)
                    .collect(Collectors.toList());
        } catch (RoadmapAiException e) {
            throw e;
        } catch (Exception e) {
            throw new RoadmapAiException("No se pudo interpretar la respuesta de Claude", e);
        }
    }
}
