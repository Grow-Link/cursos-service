package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SugerirPrerequisitosRequest(
        @NotNull Categoria categoria,
        @NotNull Nivel nivel,
        @NotBlank String titulo,
        String descripcion
) {
}
