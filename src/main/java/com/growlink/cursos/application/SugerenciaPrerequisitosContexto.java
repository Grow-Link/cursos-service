package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;

public record SugerenciaPrerequisitosContexto(String titulo, String descripcion, Categoria categoria, Nivel nivel) {
}
