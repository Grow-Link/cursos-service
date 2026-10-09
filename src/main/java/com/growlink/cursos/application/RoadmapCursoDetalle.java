package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

import java.util.List;

// un curso dentro de un roadmap guardado, con todo lo que la pantalla necesita para guiar a la persona:
// la razon de que este en su ruta, y otros cursos que cubren lo mismo (alternativas) por si el principal no le sirve
public record RoadmapCursoDetalle(Curso curso, int orden, List<Long> prerequisitoIds, String razon,
                                   List<Curso> alternativas, long totalPreguntasExamen) {
}
