package com.growlink.cursos.application;

public class CursoYaCompletadoException extends RuntimeException {

    public CursoYaCompletadoException(Long usuarioId, Long cursoId) {
        super("El usuario " + usuarioId + " ya completo el curso " + cursoId);
    }
}
