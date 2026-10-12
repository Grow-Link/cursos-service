package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;

import java.time.Instant;

// Un roadmap que esta persona ya completo del todo (ver RoadmapService.completados). usuarios-service lo
// consulta para dar una semilla de jardin por cada uno (GET /api/roadmap/completados).
public record RoadmapCompletadoDetalle(Long roadmapId, Categoria categoria, String metas, int totalCursos,
                                        int horasTotales, Instant completadoEn) {
}
