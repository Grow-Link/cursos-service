package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CrearCursoRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull Categoria categoria,
        @NotNull Nivel nivel,
        List<String> habilidades,
        String linkContenido,
        @NotNull Long publicadorUsuarioId,
        List<Long> prerequisitoIds
) {
}
