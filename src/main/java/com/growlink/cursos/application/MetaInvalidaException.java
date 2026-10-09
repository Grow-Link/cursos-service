package com.growlink.cursos.application;

// la meta ni siquiera parece una frase (muy corta, solo simbolos, letras al azar)
public class MetaInvalidaException extends RuntimeException {
    public MetaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
