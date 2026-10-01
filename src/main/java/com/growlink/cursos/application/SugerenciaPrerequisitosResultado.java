package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

import java.util.List;

public record SugerenciaPrerequisitosResultado(List<Curso> prerequisitos, boolean modoRespaldo) {
}
