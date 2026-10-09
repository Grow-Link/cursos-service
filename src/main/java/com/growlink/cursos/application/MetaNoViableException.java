package com.growlink.cursos.application;

// la meta es entendible pero no se puede cumplir con los cursos que hay (o la IA dijo que no tiene que ver
// con las areas de la plataforma). Se le explica a la persona en vez de inventarle una ruta
public class MetaNoViableException extends RuntimeException {
    public MetaNoViableException(String mensaje) {
        super(mensaje);
    }
}
