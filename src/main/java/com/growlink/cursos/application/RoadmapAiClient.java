package com.growlink.cursos.application;

import java.util.List;

// Puerto hacia la IA que arma el roadmap (y, reutilizando la misma
// infraestructura, sugiere prerequisitos al publicar un curso). Recibe el
// catalogo de cursos activos con sus prerequisitos reales y devuelve un
// subconjunto de esos cursos, cada uno con la razon de por que esta ahi
// (nunca cursos o relaciones inventadas: eso se valida siempre en
// RoadmapService, sin importar la implementacion).
//
// Si la meta de la persona no tiene que ver con lo que ofrece la plataforma, la implementacion
// lanza MetaNoViableException con una explicacion en vez de inventar una ruta.
//
// Sin CLAUDE_API_KEY la unica implementacion activa es FallbackTopologicoRoadmapAiClient. Para
// conectar la IA real solo hace falta configurar CLAUDE_API_KEY: con eso
// ClaudeRoadmapAiClient pasa a activarse (ver su @ConditionalOnExpression) y
// RoadmapService lo prefiere automaticamente, sin tocar codigo.
public interface RoadmapAiClient {

    PropuestaRoadmap generarRuta(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto);

    // Candidatos ya vienen filtrados a la categoria del curso que se esta
    // publicando: el llamador decide el alcance, esta interfaz solo elige
    // cuales de esos candidatos tienen sentido como prerequisito.
    List<Long> sugerirPrerequisitos(List<CursoGrafoNodo> candidatos, SugerenciaPrerequisitosContexto contexto);
}
