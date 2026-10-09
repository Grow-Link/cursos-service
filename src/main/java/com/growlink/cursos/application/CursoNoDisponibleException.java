package com.growlink.cursos.application;

// el curso existe pero no se puede hacer lo pedido: esta dado de baja o todavia no tiene examen
public class CursoNoDisponibleException extends RuntimeException {
    public CursoNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
