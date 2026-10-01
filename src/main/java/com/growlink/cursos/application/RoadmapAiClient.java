package com.growlink.cursos.application;

import java.util.List;

// Puerto hacia la IA que arma el roadmap (y, reutilizando la misma
// infraestructura, sugiere prerequisitos al publicar un curso). Recibe el
// catalogo de cursos activos con sus prerequisitos reales y devuelve un
// subconjunto de esos cursos (nunca cursos o relaciones inventadas: eso se
// valida siempre en RoadmapService, sin importar la implementacion).
//
// Mientras la API de Claude siga bloqueada por el tema de pago (ver README),
// la unica implementacion activa es FallbackTopologicoRoadmapAiClient. Para
// conectar la IA real solo hace falta configurar CLAUDE_API_KEY: con eso
// ClaudeRoadmapAiClient pasa a activarse (ver su @ConditionalOnExpression) y
// RoadmapService lo prefiere automaticamente, sin tocar codigo.
public interface RoadmapAiClient {

    List<Long> generarOrden(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto);

    // Candidatos ya vienen filtrados a la categoria del curso que se esta
    // publicando: el llamador decide el alcance, esta interfaz solo elige
    // cuales de esos candidatos tienen sentido como prerequisito.
    List<Long> sugerirPrerequisitos(List<CursoGrafoNodo> candidatos, SugerenciaPrerequisitosContexto contexto);
}
