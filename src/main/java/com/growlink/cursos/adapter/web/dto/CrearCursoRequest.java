package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// duracionHoras es obligatoria (1 a 500); temario y examen son opcionales para el backend, pero el formulario de publicar los pide:
// sin examen nadie puede completar el curso
public record CrearCursoRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull Categoria categoria,
        @NotNull Nivel nivel,
        List<Long> habilidadIds,
        String linkContenido,
        @NotNull Long publicadorUsuarioId,
        List<Long> prerequisitoIds,
        @NotNull @Min(1) @Max(500) Integer duracionHoras,
        List<String> temario,
        List<@Valid PreguntaExamenRequest> examen
) {
}
