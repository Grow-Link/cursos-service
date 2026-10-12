package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

import java.util.List;

// un curso dentro de un roadmap guardado, con todo lo que la pantalla necesita para guiar a la persona:
// la razon de que este en su ruta, y otros cursos que cubren lo mismo (alternativas): aprobar cualquiera de ellos
// cumple ese paso
public record RoadmapCursoDetalle(Curso curso, int orden, List<Long> prerequisitoIds, String razon,
                                   List<AlternativaDetalle> alternativas, long totalPreguntasExamen) {
}
