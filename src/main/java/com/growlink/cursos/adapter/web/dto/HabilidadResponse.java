package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Habilidad;

public record HabilidadResponse(Long id, String nombre, Categoria categoria) {

    public static HabilidadResponse from(Habilidad habilidad) {
        return new HabilidadResponse(habilidad.getId(), habilidad.getNombre(), habilidad.getCategoria());
    }
}
