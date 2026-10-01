package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.CompletadoDetalle;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

import java.time.Instant;
import java.util.List;

public record CompletadoResponse(Long cursoId, String titulo, Categoria categoria, Nivel nivel,
                                  List<HabilidadResponse> habilidades, Instant fechaCompletado,
                                  boolean disponible) {

    public static CompletadoResponse from(CompletadoDetalle detalle) {
        var curso = detalle.curso();
        return new CompletadoResponse(curso.getId(), curso.getTitulo(), curso.getCategoria(), curso.getNivel(),
                curso.getHabilidades().stream().map(HabilidadResponse::from).toList(),
                detalle.fechaCompletado(), curso.isActivo());
    }
}
