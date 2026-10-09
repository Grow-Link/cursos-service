package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

public record CursoResponse(Long id, String titulo, String descripcion, Categoria categoria, Nivel nivel,
                             List<HabilidadResponse> habilidades, String linkContenido, boolean activo,
                             Long publicadorUsuarioId, List<Long> prerequisitoIds, Integer duracionHoras) {

    public static CursoResponse from(Curso curso, List<Long> prerequisitoIds) {
        return new CursoResponse(curso.getId(), curso.getTitulo(), curso.getDescripcion(), curso.getCategoria(),
                curso.getNivel(),
                curso.getHabilidades().stream().map(HabilidadResponse::from).toList(),
                curso.getLinkContenido(), curso.isActivo(),
                curso.getPublicadorUsuarioId(), prerequisitoIds, curso.getDuracionHoras());
    }
}
