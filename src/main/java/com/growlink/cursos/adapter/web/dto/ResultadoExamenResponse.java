package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.ResultadoExamen;

public record ResultadoExamenResponse(boolean aprobado, int aciertos, int total, int porcentaje, int minimoAprobacion,
                                       boolean cursoCompletado) {
    public static ResultadoExamenResponse from(ResultadoExamen r) {
        return new ResultadoExamenResponse(r.aprobado(), r.aciertos(), r.total(), r.porcentaje(),
                r.minimoAprobacion(), r.cursoCompletado());
    }
}
