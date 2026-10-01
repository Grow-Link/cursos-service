package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Curso;

public record CursoSugeridoResponse(Long id, String titulo) {

    public static CursoSugeridoResponse from(Curso curso) {
        return new CursoSugeridoResponse(curso.getId(), curso.getTitulo());
    }
}
