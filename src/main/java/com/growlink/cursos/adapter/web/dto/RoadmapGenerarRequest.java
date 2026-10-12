package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// horasPorSemana y semanas son opcionales (se mandan los dos o ninguno): sin ellos la ruta no tiene límite de tiempo
public record RoadmapGenerarRequest(
        @NotNull Long usuarioId,
        String metas,
        List<Categoria> intereses,
        @NotNull Nivel nivel,
        @Min(1) @Max(60) Integer horasPorSemana,
        @Min(1) @Max(104) Integer semanas
) {
}
