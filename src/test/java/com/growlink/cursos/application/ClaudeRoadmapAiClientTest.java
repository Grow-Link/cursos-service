package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// No llama a Claude (eso necesita la llave y cuesta plata): prueba como se lee la respuesta
// con la forma real que devuelve la API de mensajes, incluyendo los bloques de pensamiento
// que los modelos nuevos mandan ANTES del texto
class ClaudeRoadmapAiClientTest {

    private final ClaudeRoadmapAiClient cliente =
            new ClaudeRoadmapAiClient("llave-de-prueba", "claude-sonnet-5-5", new ObjectMapper());

    @Test
    void leeLosIdsDeUnaRespuestaSimple() {
        String respuesta = """
                {"id":"msg_1","type":"message","role":"assistant","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"[3,1,7]"}]}
                """;
        assertThat(cliente.extraerListaIds(respuesta)).containsExactly(3L, 1L, 7L);
    }

    @Test
    void ignoraElBloqueDePensamientoQueVieneAntesDelTexto() {
        // antes se leia siempre el primer bloque, y con thinking ese bloque no trae la respuesta
        String respuesta = """
                {"id":"msg_2","type":"message","role":"assistant","stop_reason":"end_turn",
                 "content":[{"type":"thinking","thinking":"","signature":"abc"},
                            {"type":"text","text":"Esta es la ruta: [2, 5, 9]"}]}
                """;
        assertThat(cliente.extraerListaIds(respuesta)).containsExactly(2L, 5L, 9L);
    }

    @Test
    void unaRespuestaSinTextoSeRechazaParaQueEntreElRespaldo() {
        // por ejemplo cuando se agotan los tokens pensando y nunca llega el texto
        String soloPensamiento = """
                {"id":"msg_3","type":"message","role":"assistant","stop_reason":"max_tokens",
                 "content":[{"type":"thinking","thinking":"","signature":"abc"}]}
                """;
        assertThatThrownBy(() -> cliente.extraerListaIds(soloPensamiento))
                .isInstanceOf(RoadmapAiException.class);
    }

    @Test
    void unTextoSinArregloSeRechaza() {
        String sinArreglo = """
                {"content":[{"type":"text","text":"No puedo armar una ruta con esa informacion."}]}
                """;
        assertThatThrownBy(() -> cliente.extraerListaIds(sinArreglo))
                .isInstanceOf(RoadmapAiException.class);
    }

    @Test
    void unArregloVacioEsValido() {
        // para sugerir prerequisitos "ninguno" es una respuesta correcta
        String vacio = "{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}";
        assertThat(cliente.extraerListaIds(vacio)).isEqualTo(List.of());
    }
}
