package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoPrerequisitoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.RoadmapCursoRepository;
import com.growlink.cursos.adapter.persistence.RoadmapRepository;
import com.growlink.cursos.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoadmapService {

    private static final Logger log = LoggerFactory.getLogger(RoadmapService.class);

    private final CursoRepository cursoRepository;
    private final CursoPrerequisitoRepository prerequisitoRepository;
    private final RoadmapRepository roadmapRepository;
    private final RoadmapCursoRepository roadmapCursoRepository;
    private final FallbackTopologicoRoadmapAiClient fallbackClient;
    private final RoadmapAiClient aiClient;

    public RoadmapService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository,
                           RoadmapRepository roadmapRepository, RoadmapCursoRepository roadmapCursoRepository,
                           FallbackTopologicoRoadmapAiClient fallbackClient,
                           ObjectProvider<ClaudeRoadmapAiClient> claudeClientProvider) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
        this.roadmapRepository = roadmapRepository;
        this.roadmapCursoRepository = roadmapCursoRepository;
        this.fallbackClient = fallbackClient;
        // si CLAUDE_API_KEY esta configurada, ese bean existe y se prefiere;
        // si no, cae automaticamente al modo de respaldo
        ClaudeRoadmapAiClient claudeClient = claudeClientProvider.getIfAvailable();
        this.aiClient = claudeClient != null ? claudeClient : fallbackClient;
    }

    @Transactional
    public RoadmapDetalle generar(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        List<CursoGrafoNodo> catalogoActivo = catalogoActivoComoGrafo();
        ContextoRoadmap contexto = new ContextoRoadmap(usuarioId, metas, intereses, nivel);

        OrdenGenerado generado = generarOrdenValidado(catalogoActivo, contexto);
        List<Long> orden = generado.orden();

        Roadmap roadmap = roadmapRepository.save(new Roadmap(usuarioId, metas, nivel, generado.fuente()));
        Map<Long, Curso> cursosPorId = cursoRepository.findAllById(orden).stream()
                .collect(Collectors.toMap(Curso::getId, c -> c));

        List<RoadmapCursoDetalle> detalle = new ArrayList<>();
        for (int i = 0; i < orden.size(); i++) {
            Long cursoId = orden.get(i);
            roadmapCursoRepository.save(new RoadmapCurso(roadmap.getId(), cursoId, i));
            detalle.add(new RoadmapCursoDetalle(cursosPorId.get(cursoId), i, prerequisitosDe(cursoId)));
        }
        return new RoadmapDetalle(roadmap, detalle);
    }

    public RoadmapDetalle mio(Long usuarioId) {
        Roadmap roadmap = roadmapRepository.findFirstByUsuarioIdOrderByCreadoEnDesc(usuarioId)
                .orElseThrow(() -> new RoadmapNoEncontradoException(usuarioId));
        List<RoadmapCurso> entradas = roadmapCursoRepository.findByRoadmapIdOrderByOrdenAsc(roadmap.getId());
        List<Long> cursoIds = entradas.stream().map(RoadmapCurso::getCursoId).toList();
        Map<Long, Curso> cursosPorId = cursoRepository.findAllById(cursoIds).stream()
                .collect(Collectors.toMap(Curso::getId, c -> c));

        List<RoadmapCursoDetalle> detalle = entradas.stream()
                .map(e -> new RoadmapCursoDetalle(cursosPorId.get(e.getCursoId()), e.getOrden(),
                        prerequisitosDe(e.getCursoId())))
                .toList();
        return new RoadmapDetalle(roadmap, detalle);
    }

    private record OrdenGenerado(List<Long> orden, FuenteRoadmap fuente) {
    }

    private OrdenGenerado generarOrdenValidado(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
        // sin llave de Claude, aiClient ya es el respaldo: eso nunca se cuenta como IA
        boolean hayIa = aiClient != fallbackClient;
        List<Long> orden;
        try {
            orden = aiClient.generarOrden(catalogoActivo, contexto);
        } catch (RoadmapAiException e) {
            log.warn("Fallo la IA generando el roadmap, se usa el modo de respaldo: {}", e.getMessage());
            return new OrdenGenerado(fallbackClient.generarOrden(catalogoActivo, contexto), FuenteRoadmap.RESPALDO);
        }

        if (esSubconjuntoValido(orden, catalogoActivo)) {
            return new OrdenGenerado(orden, hayIa ? FuenteRoadmap.IA : FuenteRoadmap.RESPALDO);
        }
        log.warn("La respuesta de la IA no es un subconjunto valido del catalogo/prerequisitos reales, "
                + "se usa el modo de respaldo");
        return new OrdenGenerado(fallbackClient.generarOrden(catalogoActivo, contexto), FuenteRoadmap.RESPALDO);
    }

    // nunca se guarda un roadmap con cursos o relaciones inventadas: todo id
    // debe existir en el catalogo activo, sin repetidos, y cada curso debe
    // aparecer despues de sus prerequisitos (los que tambien esten en la ruta)
    private boolean esSubconjuntoValido(List<Long> orden, List<CursoGrafoNodo> catalogoActivo) {
        if (orden == null || orden.isEmpty()) {
            return false;
        }
        Map<Long, CursoGrafoNodo> porId = catalogoActivo.stream()
                .collect(Collectors.toMap(CursoGrafoNodo::cursoId, c -> c));

        if (new HashSet<>(orden).size() != orden.size()) {
            return false;
        }

        Set<Long> vistos = new HashSet<>();
        for (Long cursoId : orden) {
            CursoGrafoNodo nodo = porId.get(cursoId);
            if (nodo == null) {
                return false;
            }
            for (Long prerequisitoId : nodo.prerequisitoIds()) {
                if (orden.contains(prerequisitoId) && !vistos.contains(prerequisitoId)) {
                    return false;
                }
            }
            vistos.add(cursoId);
        }
        return true;
    }

    // Reutiliza el mismo RoadmapAiClient/fallback del roadmap para sugerir
    // prerequisitos al publicar un curso (formulario de "publicar curso"):
    // candidatos = cursos activos existentes de esa misma categoria.
    public SugerenciaPrerequisitosResultado sugerirPrerequisitos(Categoria categoria, Nivel nivel, String titulo,
                                                                   String descripcion) {
        List<Curso> candidatos = cursoRepository.buscarCatalogo(categoria, null);
        List<CursoGrafoNodo> candidatosGrafo = candidatos.stream()
                .map(c -> new CursoGrafoNodo(c.getId(), c.getTitulo(), c.getCategoria(), c.getNivel(),
                        prerequisitosDe(c.getId())))
                .toList();
        SugerenciaPrerequisitosContexto contexto = new SugerenciaPrerequisitosContexto(titulo, descripcion,
                categoria, nivel);

        List<Long> sugeridos;
        boolean modoRespaldo;
        try {
            sugeridos = aiClient.sugerirPrerequisitos(candidatosGrafo, contexto);
            modoRespaldo = aiClient == fallbackClient;
            if (!esSubconjuntoDeCandidatos(sugeridos, candidatosGrafo)) {
                log.warn("La respuesta de la IA sugiriendo prerequisitos no es un subconjunto valido de los "
                        + "candidatos, se usa el modo de respaldo");
                sugeridos = fallbackClient.sugerirPrerequisitos(candidatosGrafo, contexto);
                modoRespaldo = true;
            }
        } catch (RoadmapAiException e) {
            log.warn("Fallo la IA sugiriendo prerequisitos, se usa el modo de respaldo: {}", e.getMessage());
            sugeridos = fallbackClient.sugerirPrerequisitos(candidatosGrafo, contexto);
            modoRespaldo = true;
        }

        Map<Long, Curso> candidatosPorId = candidatos.stream().collect(Collectors.toMap(Curso::getId, c -> c));
        List<Curso> prerequisitos = sugeridos.stream().map(candidatosPorId::get).filter(Objects::nonNull).toList();
        return new SugerenciaPrerequisitosResultado(prerequisitos, modoRespaldo);
    }

    private boolean esSubconjuntoDeCandidatos(List<Long> ids, List<CursoGrafoNodo> candidatos) {
        if (ids == null) {
            return false;
        }
        Set<Long> validos = candidatos.stream().map(CursoGrafoNodo::cursoId).collect(Collectors.toSet());
        return new HashSet<>(ids).size() == ids.size() && validos.containsAll(ids);
    }

    private List<CursoGrafoNodo> catalogoActivoComoGrafo() {
        return cursoRepository.buscarCatalogo(null, null).stream()
                .map(c -> new CursoGrafoNodo(c.getId(), c.getTitulo(), c.getCategoria(), c.getNivel(),
                        prerequisitosDe(c.getId())))
                .toList();
    }

    private List<Long> prerequisitosDe(Long cursoId) {
        return prerequisitoRepository.findByCursoId(cursoId).stream()
                .map(CursoPrerequisito::getPrerequisitoId)
                .toList();
    }
}
