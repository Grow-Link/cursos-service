package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;

public class HabilidadCategoriaInvalidaException extends RuntimeException {

    public HabilidadCategoriaInvalidaException(Long habilidadId, Categoria categoriaHabilidad, Categoria categoriaCurso) {
        super("La habilidad " + habilidadId + " es de categoria " + categoriaHabilidad
                + " y no coincide con la categoria del curso (" + categoriaCurso + ")");
    }
}
