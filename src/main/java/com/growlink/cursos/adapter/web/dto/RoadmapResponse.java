package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapDetalle;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.FuenteRoadmap;
import com.growlink.cursos.domain.Nivel;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

// generadoPor dice si lo armo la IA o el modo de respaldo (null en roadmaps viejos)
// resumen es la frase que explica la ruta; intereses son las areas con las que se genero
public record RoadmapResponse(Long id, Long usuarioId, String metas, Nivel nivel, Instant creadoEn,
                               FuenteRoadmap generadoPor, String resumen, List<Categoria> intereses,
                               List<RoadmapCursoResponse> cursos) {

    public static RoadmapResponse from(RoadmapDetalle detalle) {
        var roadmap = detalle.roadmap();
        List<Categoria> intereses = roadmap.getIntereses() == null || roadmap.getIntereses().isBlank()
                ? List.of()
                : Arrays.stream(roadmap.getIntereses().split(",")).map(Categoria::valueOf).toList();
        return new RoadmapResponse(roadmap.getId(), roadmap.getUsuarioId(), roadmap.getMetas(), roadmap.getNivel(),
                roadmap.getCreadoEn(), roadmap.getGeneradoPor(), roadmap.getResumen(), intereses,
                detalle.cursos().stream().map(RoadmapCursoResponse::from).toList());
    }
}
