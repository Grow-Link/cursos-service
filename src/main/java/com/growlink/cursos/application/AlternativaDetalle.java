package com.growlink.cursos.application;

import com.growlink.cursos.domain.Curso;

// Otro curso que cubre todo lo que cubre un paso de la ruta (misma area, mismo nivel, y al menos sus mismas
// habilidades). Aprobar su examen cumple ese paso, por eso solo se proponen cursos que tienen examen.
public record AlternativaDetalle(Curso curso, long totalPreguntasExamen) {
}
