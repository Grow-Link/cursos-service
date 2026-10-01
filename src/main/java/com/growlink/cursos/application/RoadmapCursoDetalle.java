package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

import java.util.List;

public record RoadmapCursoDetalle(Curso curso, int orden, List<Long> prerequisitoIds) {
}
