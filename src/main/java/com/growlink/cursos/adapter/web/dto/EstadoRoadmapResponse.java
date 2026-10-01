package com.growlink.cursos.adapter.web.dto;

import java.util.List;

// HU-10: de los ids de un roadmap guardado, cuales ya no estan activos
// (el frontend lo usa para el banner de "regenera tu roadmap")
public record EstadoRoadmapResponse(List<Long> cursoIdsInactivos, boolean requiereRegenerar) {

    public static EstadoRoadmapResponse from(List<Long> inactivos) {
        return new EstadoRoadmapResponse(inactivos, !inactivos.isEmpty());
    }
}
