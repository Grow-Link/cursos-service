package com.growlink.cursos.application;

// Cualquier falla al pedirle el roadmap a la IA real (red, respuesta mal
// formada, etc). RoadmapService la atrapa y cae al modo de respaldo
// (FallbackTopologicoRoadmapAiClient) en vez de romper la generacion del roadmap.
public class RoadmapAiException extends RuntimeException {

    public RoadmapAiException(String message, Throwable cause) {
        super(message, cause);
    }

    public RoadmapAiException(String message) {
        super(message);
    }
}
