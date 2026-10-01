package com.growlink.cursos.application;

public class HabilidadNoEncontradaException extends RuntimeException {

    public HabilidadNoEncontradaException(Long habilidadId) {
        super("No existe la habilidad " + habilidadId);
    }
}
