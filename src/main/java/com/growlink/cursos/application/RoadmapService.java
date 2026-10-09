package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoPrerequisitoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.PreguntaExamenRepository;
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

    private static final int MAX_CURSOS_EN_RUTA = 12;
    private static final int MAX_ALTERNATIVAS = 3;

    private final CursoRepository cursoRepository;
    private final CursoPrerequisitoRepository prerequisitoRepository;
    private final RoadmapRepository roadmapRepository;
    private final RoadmapCursoRepository roadmapCursoRepository;
    private final PreguntaExamenRepository preguntaExamenRepository;
    private final MetaValidator metaValidator;
    private final FallbackTopologicoRoadmapAiClient fallbackClient;
    private final RoadmapAiClient aiClient;

    public RoadmapService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository,
                           RoadmapRepository roadmapRepository, RoadmapCursoRepository roadmapCursoRepository,
                           PreguntaExamenRepository preguntaExamenRepository, MetaValidator metaValidator,
                           FallbackTopologicoRoadmapAiClient fallbackClient,
                           ObjectProvider<ClaudeRoadmapAiClient> claudeClientProvider) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
        this.roadmapRepository = roadmapRepository;
        this.roadmapCursoRepository = roadmapCursoRepository;
        this.preguntaExamenRepository = preguntaExamenRepository;
        this.metaValidator = metaValidator;
        this.fallbackClient = fallbackClient;
        // si CLAUDE_API_KEY esta configurada, ese bean existe y se prefiere;
        // si no, cae automaticamente al modo de respaldo
        ClaudeRoadmapAiClient claudeClient = claudeClientProvider.getIfAvailable();
        this.aiClient = claudeClient != null ? claudeClient : fallbackClient;
        // para no tener que adivinar: al arrancar queda escrito cual de los dos se esta usando
        if (claudeClient != null) {
            log.info("Roadmap: IA ACTIVADA, se usa Claude (se encontro CLAUDE_API_KEY)");
        } else {
            log.warn("Roadmap: SIN IA, no hay CLAUDE_API_KEY y se usa el modo de respaldo");
        }
    }

    private record Generado(PropuestaRoadmap propuesta, FuenteRoadmap fuente) {
    }

    @Transactional
    public RoadmapDetalle generar(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        List<CursoGrafoNodo> catalogoActivo = catalogoActivoComoGrafo();
        ContextoRoadmap contexto = validarEntrada(usuarioId, metas, intereses, nivel, catalogoActivo);
        return guardar(contexto, generarRutaValidada(catalogoActivo, contexto));
    }

    // para sembrar el usuario de demostracion al arrancar: siempre con el respaldo, nunca gasta una llamada a la IA
    @Transactional
    public RoadmapDetalle generarConRespaldo(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        List<CursoGrafoNodo> catalogoActivo = catalogoActivoComoGrafo();
        ContextoRoadmap contexto = validarEntrada(usuarioId, metas, intereses, nivel, catalogoActivo);
        PropuestaRoadmap propuesta = fallbackClient.generarRuta(catalogoActivo, contexto);
        return guardar(contexto, new Generado(propuesta, FuenteRoadmap.RESPALDO));
    }

    // antes de gastar una llamada a la IA: la meta tiene que parecer una frase, y las areas tienen que tener cursos
    private ContextoRoadmap validarEntrada(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel,
                                           List<CursoGrafoNodo> catalogoActivo) {
        metaValidator.validar(metas);
        if (intereses == null || intereses.isEmpty()) {
            throw new MetaInvalidaException("Elige al menos un área de las que aparecen disponibles.");
        }
        Set<Categoria> conCursos = catalogoActivo.stream().map(CursoGrafoNodo::categoria).collect(Collectors.toSet());
        for (Categoria area : intereses) {
            if (!conCursos.contains(area)) {
                throw new MetaNoViableException("Todavía no hay cursos de " + area.etiqueta()
                        + ". Elige otra de las áreas que aparecen disponibles.");
            }
        }
        return new ContextoRoadmap(usuarioId, metas.trim().replaceAll("\\s+", " "), List.copyOf(intereses), nivel);
    }

    private RoadmapDetalle guardar(ContextoRoadmap contexto, Generado generado) {
        PropuestaRoadmap propuesta = generado.propuesta();
        String intereses = contexto.intereses().stream().map(Enum::name).collect(Collectors.joining(","));
        String resumen = limitar(propuesta.resumen(), 800);

        Roadmap roadmap = roadmapRepository.save(new Roadmap(contexto.usuarioId(), contexto.metas(), contexto.nivel(),
                generado.fuente(), resumen.isBlank() ? null : resumen, intereses));

        List<RoadmapCurso> entradas = new ArrayList<>();
        for (int i = 0; i < propuesta.cursos().size(); i++) {
            CursoElegido elegido = propuesta.cursos().get(i);
            String razon = limitar(elegido.razon(), 500);
            entradas.add(roadmapCursoRepository.save(new RoadmapCurso(roadmap.getId(), elegido.cursoId(), i,
                    razon.isBlank() ? "Forma parte de tu ruta hacia tu meta." : razon)));
        }
        return construirDetalle(roadmap, entradas);
    }

    public RoadmapDetalle mio(Long usuarioId) {
        Roadmap roadmap = roadmapRepository.findFirstByUsuarioIdOrderByCreadoEnDesc(usuarioId)
                .orElseThrow(() -> new RoadmapNoEncontradoException(usuarioId));
        return construirDetalle(roadmap, roadmapCursoRepository.findByRoadmapIdOrderByOrdenAsc(roadmap.getId()));
    }

    // arma lo que la pantalla necesita de cada curso de la ruta: sus datos completos, la razon de que este
    // ahi, los prerequisitos reales, y otros cursos que cubren lo mismo por si el principal no le sirve
    private RoadmapDetalle construirDetalle(Roadmap roadmap, List<RoadmapCurso> entradas) {
        List<Long> cursoIds = entradas.stream().map(RoadmapCurso::getCursoId).toList();
        Map<Long, Curso> cursosPorId = cursoRepository.findAllById(cursoIds).stream()
                .collect(Collectors.toMap(Curso::getId, c -> c));
        Set<Long> enRuta = new HashSet<>(cursoIds);
        List<Curso> catalogoActivo = cursoRepository.buscarCatalogo(null, null);

        List<RoadmapCursoDetalle> detalle = entradas.stream()
                .map(e -> {
                    Curso curso = cursosPorId.get(e.getCursoId());
                    return new RoadmapCursoDetalle(curso, e.getOrden(), prerequisitosDe(e.getCursoId()),
                            e.getRazon() == null ? "" : e.getRazon(),
                            alternativasDe(curso, enRuta, catalogoActivo),
                            preguntaExamenRepository.countByCursoId(curso.getId()));
                })
                .toList();
        return new RoadmapDetalle(roadmap, detalle);
    }

    // cursos de la misma area que no estan en la ruta y se parecen al curso (comparten habilidades; si ninguno
    // comparte, los del mismo nivel), los mas parecidos primero
    private List<Curso> alternativasDe(Curso curso, Set<Long> enRuta, List<Curso> catalogoActivo) {
        Set<Long> habilidades = curso.getHabilidades().stream().map(Habilidad::getId).collect(Collectors.toSet());
        return catalogoActivo.stream()
                .filter(c -> !c.getId().equals(curso.getId()) && !enRuta.contains(c.getId()))
                .filter(c -> c.getCategoria() == curso.getCategoria())
                .map(c -> Map.entry(c, (int) c.getHabilidades().stream().filter(h -> habilidades.contains(h.getId())).count()))
                .filter(e -> e.getValue() > 0 || e.getKey().getNivel() == curso.getNivel())
                .sorted(Comparator.comparingInt((Map.Entry<Curso, Integer> e) -> -e.getValue())
                        .thenComparingInt(e -> Math.abs(e.getKey().getNivel().ordinal() - curso.getNivel().ordinal())))
                .limit(MAX_ALTERNATIVAS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Generado generarRutaValidada(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
        // sin llave de Claude, aiClient ya es el respaldo: eso nunca se cuenta como IA
        boolean hayIa = aiClient != fallbackClient;
        PropuestaRoadmap propuesta;
        try {
            propuesta = aiClient.generarRuta(catalogoActivo, contexto);
        } catch (RoadmapAiException e) {
            // OJO: si la IA dijo que la meta no es viable (MetaNoViableException) NO se cae al respaldo,
            // se le explica a la persona. El respaldo solo entra cuando la IA fallo, no cuando dijo que no
            log.warn("Fallo la IA generando el roadmap, se usa el modo de respaldo: {}", e.getMessage());
            return new Generado(fallbackClient.generarRuta(catalogoActivo, contexto), FuenteRoadmap.RESPALDO);
        }

        if (esPropuestaValida(propuesta, catalogoActivo)) {
            return new Generado(propuesta, hayIa ? FuenteRoadmap.IA : FuenteRoadmap.RESPALDO);
        }
        log.warn("La respuesta de la IA no es un subconjunto valido del catalogo/prerequisitos reales, "
                + "se usa el modo de respaldo");
        return new Generado(fallbackClient.generarRuta(catalogoActivo, contexto), FuenteRoadmap.RESPALDO);
    }

    // nunca se guarda un roadmap con cursos o relaciones inventadas: todo id
    // debe existir en el catalogo activo (un curso dado de baja no esta ahi), sin repetidos,
    // y cada curso debe aparecer despues de sus prerequisitos (los que tambien esten en la ruta)
    private boolean esPropuestaValida(PropuestaRoadmap propuesta, List<CursoGrafoNodo> catalogoActivo) {
        if (propuesta == null || propuesta.cursos() == null || propuesta.cursos().isEmpty()
                || propuesta.cursos().size() > MAX_CURSOS_EN_RUTA) {
            return false;
        }
        List<Long> orden = propuesta.ids();
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
        List<CursoGrafoNodo> candidatosGrafo = candidatos.stream().map(this::comoNodo).toList();
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
        return cursoRepository.buscarCatalogo(null, null).stream().map(this::comoNodo).toList();
    }

    private CursoGrafoNodo comoNodo(Curso c) {
        return new CursoGrafoNodo(c.getId(), c.getTitulo(), c.getCategoria(), c.getNivel(),
                prerequisitosDe(c.getId()), c.getDuracionHoras(),
                c.getHabilidades().stream().map(Habilidad::getNombre).toList(),
                c.getDescripcion() == null ? "" : c.getDescripcion(), c.getTemario());
    }

    private List<Long> prerequisitosDe(Long cursoId) {
        return prerequisitoRepository.findByCursoId(cursoId).stream()
                .map(CursoPrerequisito::getPrerequisitoId)
                .toList();
    }

    private static String limitar(String texto, int max) {
        if (texto == null) {
            return "";
        }
        String limpio = texto.trim();
        return limpio.length() <= max ? limpio : limpio.substring(0, max - 1) + "…";
    }
}
