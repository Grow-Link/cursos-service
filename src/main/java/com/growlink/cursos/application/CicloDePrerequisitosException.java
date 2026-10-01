package com.growlink.cursos.application;

public class CicloDePrerequisitosException extends RuntimeException {
    public CicloDePrerequisitosException(Long cursoId, Long prerequisitoId) {
        super("No se puede agregar el prerequisito " + prerequisitoId + " al curso " + cursoId
                + " porque generaria un ciclo (ese curso ya depende, directa o indirectamente, de este)");
    }
}
