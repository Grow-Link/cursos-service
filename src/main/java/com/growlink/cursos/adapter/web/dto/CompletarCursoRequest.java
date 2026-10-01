package com.growlink.cursos.adapter.web.dto;

import jakarta.validation.constraints.NotNull;

public record CompletarCursoRequest(@NotNull Long usuarioId) {
}
