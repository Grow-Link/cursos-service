package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapCursoDetalle;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// Cursos del roadmap con sus relaciones de prerequisito reales, para que el
// frontend arme el grafo (HU-12 es responsabilidad del frontend).
public record RoadmapCursoResponse(Long cursoId, String titulo, Categoria categoria, Nivel nivel, int orden,
                                    List<Long> prerequisitoIds) {

    public static RoadmapCursoResponse from(RoadmapCursoDetalle detalle) {
        var curso = detalle.curso();
        return new RoadmapCursoResponse(curso.getId(), curso.getTitulo(), curso.getCategoria(), curso.getNivel(),
                detalle.orden(), detalle.prerequisitoIds());
    }
}
