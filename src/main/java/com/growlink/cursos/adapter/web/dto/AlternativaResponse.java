package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.AlternativaDetalle;
import com.growlink.cursos.domain.Habilidad;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// otro curso que cubre todo lo de un paso de la ruta: la persona puede escoger este y, al aprobar su examen,
// el paso queda cumplido (totalPreguntasExamen siempre es mayor que 0: sin examen no se propone)
public record AlternativaResponse(Long cursoId, String titulo, Nivel nivel, Integer duracionHoras,
                                   String linkContenido, List<String> habilidades, long totalPreguntasExamen) {

    public static AlternativaResponse from(AlternativaDetalle alternativa) {
        var curso = alternativa.curso();
        return new AlternativaResponse(curso.getId(), curso.getTitulo(), curso.getNivel(), curso.getDuracionHoras(),
                curso.getLinkContenido(), curso.getHabilidades().stream().map(Habilidad::getNombre).toList(),
                alternativa.totalPreguntasExamen());
    }
}
