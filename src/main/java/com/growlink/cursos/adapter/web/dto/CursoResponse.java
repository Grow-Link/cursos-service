package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

public record CursoResponse(Long id, String titulo, String descripcion, Categoria categoria, String categoriaEtiqueta,
                             Nivel nivel, List<HabilidadResponse> habilidades, String linkContenido, boolean activo,
                             Long publicadorUsuarioId, List<Long> prerequisitoIds, Integer duracionHoras,
                             List<String> temario, long totalPreguntasExamen) {

    public static CursoResponse from(Curso curso, List<Long> prerequisitoIds, long totalPreguntasExamen) {
        return new CursoResponse(curso.getId(), curso.getTitulo(), curso.getDescripcion(), curso.getCategoria(),
                curso.getCategoria().etiqueta(), curso.getNivel(),
                curso.getHabilidades().stream().map(HabilidadResponse::from).toList(),
                curso.getLinkContenido(), curso.isActivo(),
                curso.getPublicadorUsuarioId(), prerequisitoIds, curso.getDuracionHoras(), curso.getTemario(),
                totalPreguntasExamen);
    }
}
