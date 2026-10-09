package com.growlink.cursos.application;

// el examen que se quiere guardar, o las respuestas que se mandan, no cumplen las reglas
public class ExamenInvalidoException extends RuntimeException {
    public ExamenInvalidoException(String mensaje) {
        super(mensaje);
    }
}
