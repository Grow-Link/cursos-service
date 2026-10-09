package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Nivel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// HU-08: titulo, descripcion, nivel, habilidades, link, horas y temario. La categoria no se
// edita porque las habilidades estan atadas a ella (ver Curso.editar).
// examen: si viene se reemplaza el examen completo, si no viene el curso conserva el que tenia.
public record EditarCursoRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull Nivel nivel,
        List<Long> habilidadIds,
        String linkContenido,
        @NotNull @Min(1) @Max(500) Integer duracionHoras,
        List<String> temario,
        List<@Valid PreguntaExamenRequest> examen
) {
}
