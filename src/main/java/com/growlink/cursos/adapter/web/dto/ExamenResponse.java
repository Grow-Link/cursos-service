package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.PreguntaExamen;

import java.util.List;

// el examen que ve el estudiante: las preguntas y sus opciones, NUNCA cual es la correcta
public record ExamenResponse(Long cursoId, String titulo, int minimoAprobacion, boolean yaCompletado,
                              List<PreguntaPublica> preguntas) {

    public record PreguntaPublica(Long id, String enunciado, List<String> opciones) {
        public static PreguntaPublica from(PreguntaExamen p) {
            return new PreguntaPublica(p.getId(), p.getEnunciado(), p.getOpciones());
        }
    }
}
