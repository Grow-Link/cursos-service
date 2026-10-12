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
// horasPorSemana, semanas y presupuestoHoras son el tiempo con el que se armo (null si no habia limite);
// horasTotales es lo que suma la ruta, y siempre es <= presupuestoHoras cuando hay presupuesto
public record RoadmapResponse(Long id, Long usuarioId, String metas, Nivel nivel, Instant creadoEn,
                               FuenteRoadmap generadoPor, String resumen, List<Categoria> intereses,
                               Integer horasPorSemana, Integer semanas, Integer presupuestoHoras, int horasTotales,
                               List<RoadmapCursoResponse> cursos) {

    public static RoadmapResponse from(RoadmapDetalle detalle) {
        var roadmap = detalle.roadmap();
        List<Categoria> intereses = roadmap.getIntereses() == null || roadmap.getIntereses().isBlank()
                ? List.of()
                : Arrays.stream(roadmap.getIntereses().split(",")).map(Categoria::valueOf).toList();
        List<RoadmapCursoResponse> cursos = detalle.cursos().stream().map(RoadmapCursoResponse::from).toList();
        int horasTotales = cursos.stream().mapToInt(c -> c.duracionHoras() == null ? 0 : c.duracionHoras()).sum();
        Integer presupuesto = roadmap.getHorasPorSemana() == null || roadmap.getSemanas() == null
                ? null : roadmap.getHorasPorSemana() * roadmap.getSemanas();
        return new RoadmapResponse(roadmap.getId(), roadmap.getUsuarioId(), roadmap.getMetas(), roadmap.getNivel(),
                roadmap.getCreadoEn(), roadmap.getGeneradoPor(), roadmap.getResumen(), intereses,
                roadmap.getHorasPorSemana(), roadmap.getSemanas(), presupuesto, horasTotales, cursos);
    }
}
