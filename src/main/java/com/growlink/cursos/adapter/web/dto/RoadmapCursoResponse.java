package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.RoadmapCursoDetalle;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Habilidad;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// Un curso de la ruta con todo lo que la pantalla necesita: sus relaciones de prerequisito reales (para armar el
// grafo), la razon de que este en la ruta de esta persona, sus datos completos y otros cursos parecidos.
public record RoadmapCursoResponse(Long cursoId, String titulo, Categoria categoria, String categoriaEtiqueta,
                                    Nivel nivel, int orden, List<Long> prerequisitoIds, String razon,
                                    String descripcion, Integer duracionHoras, List<String> temario,
                                    List<String> habilidades, String linkContenido, boolean activo,
                                    long totalPreguntasExamen, List<AlternativaResponse> alternativas) {

    public static RoadmapCursoResponse from(RoadmapCursoDetalle detalle) {
        var curso = detalle.curso();
        return new RoadmapCursoResponse(curso.getId(), curso.getTitulo(), curso.getCategoria(),
                curso.getCategoria().etiqueta(), curso.getNivel(), detalle.orden(), detalle.prerequisitoIds(),
                detalle.razon(), curso.getDescripcion(), curso.getDuracionHoras(), curso.getTemario(),
                curso.getHabilidades().stream().map(Habilidad::getNombre).toList(), curso.getLinkContenido(),
                curso.isActivo(), detalle.totalPreguntasExamen(),
                detalle.alternativas().stream().map(AlternativaResponse::from).toList());
    }
}
