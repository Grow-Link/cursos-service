package com.growlink.cursos.application;

// lo que pasa al presentar un examen. No se dice cuales preguntas fallo ni cual era la correcta, para que
// no se pueda descubrir el examen a base de reintentos
public record ResultadoExamen(boolean aprobado, int aciertos, int total, int porcentaje, int minimoAprobacion,
                              boolean cursoCompletado) {
}
