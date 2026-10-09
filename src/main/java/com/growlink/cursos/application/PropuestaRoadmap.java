package com.growlink.cursos.application;

import java.util.List;

// lo que contesta la IA (o el respaldo) al armar un roadmap: los cursos en orden, cada uno con su razon,
// y una frase que resume la ruta. RoadmapService valida todo esto antes de guardarlo
public record PropuestaRoadmap(List<CursoElegido> cursos, String resumen) {

    public List<Long> ids() {
        return cursos.stream().map(CursoElegido::cursoId).toList();
    }
}
