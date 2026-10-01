package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

import java.util.List;

public record ContextoRoadmap(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
}
