package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Habilidad;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// otro curso que cubre algo parecido a un curso de la ruta, por si el principal no le sirve a la persona
public record AlternativaResponse(Long cursoId, String titulo, Nivel nivel, Integer duracionHoras,
                                   String linkContenido, List<String> habilidades) {

    public static AlternativaResponse from(Curso curso) {
        return new AlternativaResponse(curso.getId(), curso.getTitulo(), curso.getNivel(), curso.getDuracionHoras(),
                curso.getLinkContenido(), curso.getHabilidades().stream().map(Habilidad::getNombre).toList());
    }
}
