package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapDetalle;
import com.growlink.cursos.domain.FuenteRoadmap;
import com.growlink.cursos.domain.Nivel;

import java.time.Instant;
import java.util.List;

// generadoPor dice si lo armo la IA o el modo de respaldo (null en roadmaps viejos)
public record RoadmapResponse(Long id, Long usuarioId, String metas, Nivel nivel, Instant creadoEn,
                               FuenteRoadmap generadoPor, List<RoadmapCursoResponse> cursos) {

    public static RoadmapResponse from(RoadmapDetalle detalle) {
        var roadmap = detalle.roadmap();
        return new RoadmapResponse(roadmap.getId(), roadmap.getUsuarioId(), roadmap.getMetas(), roadmap.getNivel(),
                roadmap.getCreadoEn(), roadmap.getGeneradoPor(),
                detalle.cursos().stream().map(RoadmapCursoResponse::from).toList());
    }
}
