package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.SugerenciaPrerequisitosResultado;

import java.util.List;

public record SugerirPrerequisitosResponse(List<CursoSugeridoResponse> prerequisitos, boolean modoRespaldo) {

    public static SugerirPrerequisitosResponse from(SugerenciaPrerequisitosResultado resultado) {
        return new SugerirPrerequisitosResponse(
                resultado.prerequisitos().stream().map(CursoSugeridoResponse::from).toList(),
                resultado.modoRespaldo());
    }
}
