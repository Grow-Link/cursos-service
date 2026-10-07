package com.growlink.cursos.application;

public class NoAutorizadoException extends RuntimeException {

    public NoAutorizadoException() {
        super("No tienes permiso para realizar esta accion sobre este curso");
    }

    public NoAutorizadoException(String mensaje) {
        super(mensaje);
    }
}
