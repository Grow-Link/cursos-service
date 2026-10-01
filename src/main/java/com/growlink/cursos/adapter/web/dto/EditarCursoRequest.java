package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Nivel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// HU-08: titulo, descripcion, nivel, habilidades, link. La categoria no se
// edita porque las habilidades estan atadas a ella (ver Curso.editar).
public record EditarCursoRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull Nivel nivel,
        List<Long> habilidadIds,
        String linkContenido
) {
}
