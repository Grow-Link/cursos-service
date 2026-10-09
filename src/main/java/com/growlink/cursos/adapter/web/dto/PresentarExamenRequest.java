package com.growlink.cursos.adapter.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

// respuestas[i] es la opcion elegida (0 a 3) para la pregunta i, o -1 si la dejo en blanco
public record PresentarExamenRequest(@NotNull List<Integer> respuestas) {
}
