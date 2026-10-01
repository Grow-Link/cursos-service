package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

import java.time.Instant;

public record CompletadoDetalle(Curso curso, Instant fechaCompletado) {
}
