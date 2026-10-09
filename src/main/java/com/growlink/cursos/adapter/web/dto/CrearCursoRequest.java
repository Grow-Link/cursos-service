package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CrearCursoRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull Categoria categoria,
        @NotNull Nivel nivel,
        List<Long> habilidadIds,
        String linkContenido,
        @NotNull Long publicadorUsuarioId,
        List<Long> prerequisitoIds,
        @NotNull @Min(1) @Max(500) Integer duracionHoras
) {
}
