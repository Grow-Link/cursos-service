package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RoadmapGenerarRequest(
        @NotNull Long usuarioId,
        String metas,
        List<Categoria> intereses,
        @NotNull Nivel nivel
) {
}
