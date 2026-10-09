package com.growlink.cursos.application;

import java.util.List;

// una pregunta de examen tal como la escribe quien publica el curso
public record PreguntaExamenInput(String enunciado, List<String> opciones, int respuestaCorrecta) {
}
