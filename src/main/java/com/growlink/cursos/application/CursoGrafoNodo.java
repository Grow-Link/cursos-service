package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

// Un curso del catalogo activo, tal como se le manda a RoadmapAiClient:
// el grafo completo de cursos + prerequisitos reales, nunca inventado.
public record CursoGrafoNodo(Long cursoId, String titulo, Categoria categoria, Nivel nivel,
                              List<Long> prerequisitoIds) {
}
