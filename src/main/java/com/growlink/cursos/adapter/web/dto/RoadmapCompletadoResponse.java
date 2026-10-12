package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapCompletadoDetalle;
import com.growlink.cursos.domain.Categoria;

import java.time.Instant;

// CONTRATO con usuarios-service (GET /api/roadmap/completados): da una semilla de jardin por cada roadmap
// completado. No cambiar la forma de esta respuesta sin avisar antes (ver RoadmapController).
public record RoadmapCompletadoResponse(Long roadmapId, Categoria categoria, String titulo, int totalCursos,
                                         int horasTotales, Instant completadoEn) {

    private static final int MAX_TITULO = 80;

    public static RoadmapCompletadoResponse from(RoadmapCompletadoDetalle detalle) {
        return new RoadmapCompletadoResponse(detalle.roadmapId(), detalle.categoria(),
                recortar(detalle.metas(), MAX_TITULO), detalle.totalCursos(), detalle.horasTotales(),
                detalle.completadoEn());
    }

    // recorta a lo sumo a MAX_TITULO caracteres sin partir una palabra a la mitad: si hay que cortar, retrocede
    // hasta el ultimo espacio antes del limite
    private static String recortar(String texto, int max) {
        String limpio = texto == null ? "" : texto.trim();
        if (limpio.length() <= max) {
            return limpio;
        }
        String cortado = limpio.substring(0, max);
        int ultimoEspacio = cortado.lastIndexOf(' ');
        if (ultimoEspacio > 0) {
            cortado = cortado.substring(0, ultimoEspacio);
        }
        return cortado.trim() + "…";
    }
}
