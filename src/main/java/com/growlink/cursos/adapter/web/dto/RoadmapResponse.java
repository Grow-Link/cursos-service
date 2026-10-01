package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapDetalle;
import com.growlink.cursos.domain.Nivel;

import java.time.Instant;
import java.util.List;

public record RoadmapResponse(Long id, Long usuarioId, String metas, Nivel nivel, Instant creadoEn,
                               List<RoadmapCursoResponse> cursos) {

    public static RoadmapResponse from(RoadmapDetalle detalle) {
        var roadmap = detalle.roadmap();
        return new RoadmapResponse(roadmap.getId(), roadmap.getUsuarioId(), roadmap.getMetas(), roadmap.getNivel(),
                roadmap.getCreadoEn(), detalle.cursos().stream().map(RoadmapCursoResponse::from).toList());
    }
}
