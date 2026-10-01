package com.growlink.cursos.application;

import com.growlink.cursos.domain.Roadmap;

import java.util.List;

public record RoadmapDetalle(Roadmap roadmap, List<RoadmapCursoDetalle> cursos) {
}
