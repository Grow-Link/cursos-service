package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.application.PreguntaExamenInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// una pregunta del examen que escribe el publicador. Las reglas finas (4 opciones distintas, etc.) las revisa ExamenService
public record PreguntaExamenRequest(@NotBlank String enunciado, @NotNull List<String> opciones,
                                     @NotNull Integer respuestaCorrecta) {

    public PreguntaExamenInput aEntrada() {
        return new PreguntaExamenInput(enunciado, opciones, respuestaCorrecta);
    }
}
