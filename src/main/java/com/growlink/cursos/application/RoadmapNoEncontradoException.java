package com.growlink.cursos.application;

public class RoadmapNoEncontradoException extends RuntimeException {

    public RoadmapNoEncontradoException(Long usuarioId) {
        super("El usuario " + usuarioId + " todavia no tiene un roadmap generado");
    }
}
