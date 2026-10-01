package com.growlink.cursos.application;

public class CursoNoEncontradoException extends RuntimeException {
    public CursoNoEncontradoException(Long cursoId) {
        super("No existe el curso " + cursoId);
    }
}
