package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// Lo que se sabe de la persona al armar su ruta. horasPorSemana y semanas son opcionales (los dos o ninguno):
// juntos dan el tope de horas que puede sumar la ruta. correccion solo se usa cuando la IA se pasó del tiempo
// y se le pide, una sola vez, que lo corrija.
public record ContextoRoadmap(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel,
                              Integer horasPorSemana, Integer semanas, String correccion) {

    public ContextoRoadmap(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        this(usuarioId, metas, intereses, nivel, null, null, null);
    }

    /** El tope de horas de toda la ruta, o null si la persona no puso límite de tiempo. */
    public Integer presupuestoHoras() {
        return horasPorSemana == null || semanas == null ? null : horasPorSemana * semanas;
    }

    public ContextoRoadmap conCorreccion(String texto) {
        return new ContextoRoadmap(usuarioId, metas, intereses, nivel, horasPorSemana, semanas, texto);
    }
}
