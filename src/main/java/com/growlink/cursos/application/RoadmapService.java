package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.adapter.persistence.CursoPrerequisitoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.PreguntaExamenRepository;
import com.growlink.cursos.adapter.persistence.RoadmapCursoRepository;
import com.growlink.cursos.adapter.persistence.RoadmapRepository;
import com.growlink.cursos.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoadmapService {

    private static final Logger log = LoggerFactory.getLogger(RoadmapService.class);

    private static final int MAX_CURSOS_EN_RUTA = 12;
    private static final int MAX_ALTERNATIVAS = 3;
    // cerrojos para que dos peticiones IGUALES que llegan a la vez no le cuesten dos llamadas a la IA
    private static final int CERROJOS = 256;

    private final CursoRepository cursoRepository;
    private final CursoPrerequisitoRepository prerequisitoRepository;
    private final RoadmapRepository roadmapRepository;
    private final RoadmapCursoRepository roadmapCursoRepository;
    private final PreguntaExamenRepository preguntaExamenRepository;
    private final CursoCompletadoRepository completadoRepository;
    private final MetaValidator metaValidator;
    private final FallbackTopologicoRoadmapAiClient fallbackClient;
    private final RoadmapAiClient aiClient;
    private final PropuestaCache propuestaCache;
    private final MetricasDeRoadmap metricas;
    private final int maxAreas;
    private final Object[] cerrojos = new Object[CERROJOS];

    public RoadmapService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository,
                           RoadmapRepository roadmapRepository, RoadmapCursoRepository roadmapCursoRepository,
                           PreguntaExamenRepository preguntaExamenRepository,
                           CursoCompletadoRepository completadoRepository, MetaValidator metaValidator,
                           FallbackTopologicoRoadmapAiClient fallbackClient, PropuestaCache propuestaCache,
                           MetricasDeRoadmap metricas,
                           @Value("${roadmap.plan.max-areas:1}") int maxAreas,
                           ObjectProvider<ClaudeRoadmapAiClient> claudeClientProvider) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
        this.roadmapRepository = roadmapRepository;
        this.roadmapCursoRepository = roadmapCursoRepository;
        this.preguntaExamenRepository = preguntaExamenRepository;
        this.completadoRepository = completadoRepository;
        this.metaValidator = metaValidator;
        this.fallbackClient = fallbackClient;
        this.propuestaCache = propuestaCache;
        this.metricas = metricas;
        this.maxAreas = maxAreas;
        Arrays.setAll(cerrojos, i -> new Object());
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

    // sin tiempo: la ruta no tiene limite de horas
    @Transactional
    public RoadmapDetalle generar(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        return generar(usuarioId, metas, intereses, nivel, null, null);
    }

    // con tiempo (horas por semana x semanas): la suma de horas de la ruta nunca pasa de ese tope
    @Transactional
    public RoadmapDetalle generar(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel,
                                  Integer horasPorSemana, Integer semanas) {
        List<CursoGrafoNodo> catalogoActivo = catalogoActivoComoGrafo();
        List<CursoGrafoNodo> catalogoDisponible = sinCompletados(usuarioId, catalogoActivo);
        ContextoRoadmap contexto = validarEntrada(usuarioId, metas, intereses, nivel, horasPorSemana, semanas,
                catalogoActivo, catalogoDisponible);
        Generado generado = generarRutaValidada(catalogoDisponible, contexto);
        metricas.generacion(generado.fuente());
        return guardar(contexto, generado);
    }

    // para sembrar el usuario de demostracion al arrancar: siempre con el respaldo, nunca gasta una llamada a la IA
    @Transactional
    public RoadmapDetalle generarConRespaldo(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel) {
        List<CursoGrafoNodo> catalogoActivo = catalogoActivoComoGrafo();
        List<CursoGrafoNodo> catalogoDisponible = sinCompletados(usuarioId, catalogoActivo);
        ContextoRoadmap contexto = validarEntrada(usuarioId, metas, intereses, nivel, null, null, catalogoActivo,
                catalogoDisponible);
        PropuestaRoadmap propuesta = fallbackClient.generarRuta(catalogoDisponible, contexto);
        return guardar(contexto, new Generado(propuesta, FuenteRoadmap.RESPALDO));
    }

    // ANTI-FARMING (parte 1): un roadmap nuevo solo puede ofrecer cursos que esta persona AUN NO completo, para
    // que no se pueda "ganar" una semilla de jardin re-incluyendo trabajo que ya hizo. Un curso completado que
    // era prerequisito de otro queda simplemente afuera de este catalogo: para quien arma la ruta (la IA o el
    // respaldo) ya no existe, asi que no bloquea el orden ni se vuelve a pedir (ver
    // FallbackTopologicoRoadmapAiClient.conPrerequisitos, que ya ignora un prerequisito que no encuentra en el
    // catalogo que se le paso).
    private List<CursoGrafoNodo> sinCompletados(Long usuarioId, List<CursoGrafoNodo> catalogoActivo) {
        Set<Long> completados = completadoRepository.findByUsuarioId(usuarioId).stream()
                .map(CursoCompletado::getCursoId).collect(Collectors.toSet());
        if (completados.isEmpty()) {
            return catalogoActivo;
        }
        return catalogoActivo.stream().filter(c -> !completados.contains(c.cursoId())).toList();
    }

    // antes de gastar una llamada a la IA: la meta tiene que parecer una frase, y las areas tienen que tener
    // cursos (catalogoActivo, sin importar completados) que ademas esta persona no haya completado ya
    // (catalogoDisponible, parte 1 del anti-farming)
    private ContextoRoadmap validarEntrada(Long usuarioId, String metas, List<Categoria> intereses, Nivel nivel,
                                           Integer horasPorSemana, Integer semanas,
                                           List<CursoGrafoNodo> catalogoActivo, List<CursoGrafoNodo> catalogoDisponible) {
        metaValidator.validar(metas);
        if ((horasPorSemana == null) != (semanas == null)) {
            throw new MetaInvalidaException("Indica las horas por semana y el plazo, o deja el tiempo sin límite.");
        }
        if (intereses == null || intereses.isEmpty()) {
            throw new MetaInvalidaException("Elige al menos un área de las que aparecen disponibles.");
        }
        // PLAN GRATUITO: un roadmap es de un solo tema, o sea de una sola area. Varias areas a la vez serian del plan
        // premium (cuando exista, este tope saldra del plan de la persona; hoy es una propiedad del servicio)
        if (new HashSet<>(intereses).size() > maxAreas) {
            throw new LimiteDelPlanException("El plan gratuito arma cada roadmap sobre un solo tema (una sola área). "
                    + "Elige la que más te importa ahora; varias áreas a la vez estarán en el plan premium.");
        }
        Set<Categoria> conCursos = catalogoActivo.stream().map(CursoGrafoNodo::categoria).collect(Collectors.toSet());
        for (Categoria area : intereses) {
            if (!conCursos.contains(area)) {
                throw new MetaNoViableException("Todavía no hay cursos de " + area.etiqueta()
                        + ". Elige otra de las áreas que aparecen disponibles.");
            }
        }
        Set<Categoria> conCursosDisponibles = catalogoDisponible.stream().map(CursoGrafoNodo::categoria)
                .collect(Collectors.toSet());
        for (Categoria area : intereses) {
            if (!conCursosDisponibles.contains(area)) {
                throw new MetaNoViableException("Ya completaste todos los cursos de " + area.etiqueta()
                        + ". ¡Bien hecho! Elige otra área, o vuelve cuando se publiquen cursos nuevos.");
            }
        }
        return new ContextoRoadmap(usuarioId, metas.trim().replaceAll("\\s+", " "), List.copyOf(intereses), nivel,
                horasPorSemana, semanas, null);
    }

    private RoadmapDetalle guardar(ContextoRoadmap contexto, Generado generado) {
        PropuestaRoadmap propuesta = generado.propuesta();
        String intereses = contexto.intereses().stream().map(Enum::name).collect(Collectors.joining(","));
        String resumen = limitar(propuesta.resumen(), 800);

        Roadmap roadmap = roadmapRepository.save(new Roadmap(contexto.usuarioId(), contexto.metas(), contexto.nivel(),
                generado.fuente(), resumen.isBlank() ? null : resumen, intereses, contexto.horasPorSemana(),
                contexto.semanas()));

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

    // usuarios-service pregunta esto para dar una semilla de jardin por cada roadmap completado (CONTRATO, ver
    // RoadmapController). Generar un roadmap nuevo NUNCA borra ni reemplaza los anteriores (ver Roadmap), asi que
    // uno ya completado no desaparece: se revisan TODOS los roadmaps de la persona, no solo el ultimo ("mio").
    // Todo en consultas de bloque (IN), nunca una consulta por roadmap o por curso.
    //
    // ANTI-FARMING (parte 2): sin esto, se podrian ganar semillas sin trabajo real de dos formas. Por eso un
    // roadmap cuenta como completado solo si:
    //   (a) tiene al menos un curso, y TODOS sus cursos se completaron DESPUES de que el roadmap se creo (un
    //       curso ya completado antes de generar este roadmap no es trabajo hecho PARA el; esto tambien protege
    //       a los roadmaps viejos, de antes de la parte 1, que todavia podian incluir cursos ya completados), y
    //   (b) ninguno de sus cursos ya alimento a OTRO roadmap que se completo antes (cada curso completado paga
    //       como maximo una semilla): se recorren los candidatos que cumplen (a) en el orden real en que se
    //       completaron (completadoEn ascendente) y el primero en llegar a cada curso se lo queda.
    public List<RoadmapCompletadoDetalle> completados(Long usuarioId) {
        List<Roadmap> roadmaps = roadmapRepository.findByUsuarioIdOrderByCreadoEnAsc(usuarioId);
        if (roadmaps.isEmpty()) {
            return List.of();
        }
        List<Long> roadmapIds = roadmaps.stream().map(Roadmap::getId).toList();
        Map<Long, List<RoadmapCurso>> entradasPorRoadmap = roadmapCursoRepository.findByRoadmapIdIn(roadmapIds)
                .stream().collect(Collectors.groupingBy(RoadmapCurso::getRoadmapId));

        Set<Long> cursoIds = entradasPorRoadmap.values().stream().flatMap(List::stream)
                .map(RoadmapCurso::getCursoId).collect(Collectors.toSet());
        Map<Long, Curso> cursosPorId = cursoRepository.findAllById(cursoIds).stream()
                .collect(Collectors.toMap(Curso::getId, c -> c));
        Map<Long, Instant> completadoEnPorCurso = completadoRepository.findByUsuarioId(usuarioId).stream()
                .collect(Collectors.toMap(CursoCompletado::getCursoId, CursoCompletado::getFecha));

        List<CandidatoCompletado> candidatos = new ArrayList<>();
        for (Roadmap roadmap : roadmaps) {
            List<RoadmapCurso> entradas = entradasPorRoadmap.get(roadmap.getId());
            if (entradas == null || entradas.isEmpty()) {
                continue; // sin cursos: no cuenta
            }
            Instant completadoEn = completadoEnSiTodosSonPosterioresA(entradas, completadoEnPorCurso, roadmap.getCreadoEn());
            if (completadoEn != null) {
                candidatos.add(new CandidatoCompletado(roadmap, entradas, completadoEn));
            }
        }

        // se consume en el orden real de finalizacion: el primer roadmap en completarse se queda con sus cursos
        candidatos.sort(Comparator.comparing(CandidatoCompletado::completadoEn));
        Set<Long> cursosYaConsumidos = new HashSet<>();
        List<RoadmapCompletadoDetalle> resultado = new ArrayList<>();
        for (CandidatoCompletado candidato : candidatos) {
            List<Long> susCursoIds = candidato.entradas().stream().map(RoadmapCurso::getCursoId).toList();
            if (susCursoIds.stream().anyMatch(cursosYaConsumidos::contains)) {
                continue; // alguno de sus cursos ya le dio la semilla a un roadmap que se completo antes
            }
            cursosYaConsumidos.addAll(susCursoIds);
            List<RoadmapCurso> porOrden = candidato.entradas().stream()
                    .sorted(Comparator.comparingInt(RoadmapCurso::getOrden)).toList();
            int horasTotales = porOrden.stream().mapToInt(e -> {
                Curso curso = cursosPorId.get(e.getCursoId());
                return curso == null || curso.getDuracionHoras() == null ? 0 : curso.getDuracionHoras();
            }).sum();
            resultado.add(new RoadmapCompletadoDetalle(candidato.roadmap().getId(),
                    categoriaMayoritaria(porOrden, cursosPorId), candidato.roadmap().getMetas(), porOrden.size(),
                    horasTotales, candidato.completadoEn()));
        }
        return resultado;
    }

    private record CandidatoCompletado(Roadmap roadmap, List<RoadmapCurso> entradas, Instant completadoEn) {
    }

    // null si falta completar algun curso del roadmap, o si alguno se completo en o antes de creadoEn (no cuenta
    // como trabajo hecho para ESTE roadmap). Si todos califican, la fecha del ultimo completado (completadoEn).
    private Instant completadoEnSiTodosSonPosterioresA(List<RoadmapCurso> entradas,
                                                         Map<Long, Instant> completadoEnPorCurso, Instant creadoEn) {
        Instant masReciente = null;
        for (RoadmapCurso entrada : entradas) {
            Instant fecha = completadoEnPorCurso.get(entrada.getCursoId());
            if (fecha == null || !fecha.isAfter(creadoEn)) {
                return null;
            }
            if (masReciente == null || fecha.isAfter(masReciente)) {
                masReciente = fecha;
            }
        }
        return masReciente;
    }

    // la categoria con mas cursos del roadmap; en empate, la del curso que aparece primero por orden. Al recorrer
    // en orden y actualizar solo con un conteo ESTRICTAMENTE mayor, la primera categoria en alcanzar el conteo
    // final mas alto se queda con el premio aunque otra la empate despues.
    private Categoria categoriaMayoritaria(List<RoadmapCurso> porOrden, Map<Long, Curso> cursosPorId) {
        Map<Categoria, Integer> conteo = new HashMap<>();
        for (RoadmapCurso e : porOrden) {
            Curso curso = cursosPorId.get(e.getCursoId());
            if (curso != null) {
                conteo.merge(curso.getCategoria(), 1, Integer::sum);
            }
        }
        Categoria mejor = null;
        int mejorConteo = -1;
        for (RoadmapCurso e : porOrden) {
            Curso curso = cursosPorId.get(e.getCursoId());
            if (curso == null) {
                continue;
            }
            int conteoDeEsta = conteo.get(curso.getCategoria());
            if (conteoDeEsta > mejorConteo) {
                mejorConteo = conteoDeEsta;
                mejor = curso.getCategoria();
            }
        }
        return mejor;
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

    // ALTERNATIVAS de un paso: cursos de la misma area y el MISMO nivel que cubren todo lo que cubre ese paso (al menos
    // sus mismas habilidades), que no estan ya en la ruta y que tienen examen. Si la persona aprueba cualquiera de
    // ellos, el paso queda cumplido. Los mas parecidos primero (los que cubren menos de mas). Si no hay ninguno
    // equivalente la lista queda vacia: mejor nada que algo que no sirve para cumplir el paso.
    private List<AlternativaDetalle> alternativasDe(Curso curso, Set<Long> enRuta, List<Curso> catalogoActivo) {
        Set<Long> habilidades = curso.getHabilidades().stream().map(Habilidad::getId).collect(Collectors.toSet());
        if (habilidades.isEmpty()) {
            return List.of();
        }
        return catalogoActivo.stream()
                .filter(c -> !c.getId().equals(curso.getId()) && !enRuta.contains(c.getId()))
                .filter(c -> c.getCategoria() == curso.getCategoria() && c.getNivel() == curso.getNivel())
                .filter(c -> c.getHabilidades().stream().map(Habilidad::getId).collect(Collectors.toSet()).containsAll(habilidades))
                .map(c -> new AlternativaDetalle(c, preguntaExamenRepository.countByCursoId(c.getId())))
                .filter(a -> a.totalPreguntasExamen() > 0)
                .sorted(Comparator.comparingInt((AlternativaDetalle a) -> a.curso().getHabilidades().size())
                        .thenComparing(a -> a.curso().getId()))
                .limit(MAX_ALTERNATIVAS)
                .toList();
    }

    private Generado generarRutaValidada(List<CursoGrafoNodo> catalogoDisponible, ContextoRoadmap contexto) {
        // sin llave de Claude, aiClient ya es el respaldo: eso nunca se cuenta como IA
        boolean hayIa = aiClient != fallbackClient;
        if (!hayIa) {
            return resolverRuta(catalogoDisponible, contexto, null, false);
        }
        // si llegan dos peticiones IGUALES a la vez, la segunda espera a la primera y luego reutiliza su propuesta:
        // una sola llamada a la IA en vez de dos. Peticiones distintas no se esperan entre si (salvo choque raro de cerrojo).
        String clave = claveDe(contexto, catalogoDisponible);
        synchronized (cerrojos[Math.floorMod(clave.hashCode(), CERROJOS)]) {
            return resolverRuta(catalogoDisponible, contexto, clave, true);
        }
    }

    private Generado resolverRuta(List<CursoGrafoNodo> catalogoDisponible, ContextoRoadmap contexto, String clave,
                                  boolean hayIa) {
        Integer presupuesto = contexto.presupuestoHoras();

        // DETERMINISMO: si ya le preguntamos esto mismo a la IA (misma meta, areas, nivel, tiempo y catalogo
        // disponible, que ya excluye lo que esta persona completo), se reutiliza su propuesta validada en vez
        // de volver a preguntar; la IA por si sola puede contestar distinto
        if (clave != null) {
            Optional<PropuestaRoadmap> guardada = propuestaCache.buscar(clave);
            if (guardada.isPresent() && esPropuestaValida(guardada.get(), catalogoDisponible)
                    && (presupuesto == null || horasDe(guardada.get(), catalogoDisponible) <= presupuesto)) {
                metricas.cache(true);
                log.info("Roadmap: se reutiliza la propuesta de la IA ya guardada para esta misma entrada");
                return new Generado(guardada.get(), FuenteRoadmap.IA);
            }
            metricas.cache(false);
        }

        PropuestaRoadmap propuesta;
        try {
            propuesta = preguntarALaIa(catalogoDisponible, contexto, hayIa);
        } catch (RoadmapAiException e) {
            // OJO: si la IA dijo que la meta no es viable (MetaNoViableException) NO se cae al respaldo,
            // se le explica a la persona. El respaldo solo entra cuando la IA fallo, no cuando dijo que no
            log.warn("Fallo la IA generando el roadmap, se usa el modo de respaldo: {}", e.getMessage());
            return new Generado(fallbackClient.generarRuta(catalogoDisponible, contexto), FuenteRoadmap.RESPALDO);
        }

        if (esPropuestaValida(propuesta, catalogoDisponible)) {
            int horas = horasDe(propuesta, catalogoDisponible);
            if (presupuesto == null || horas <= presupuesto) {
                contarPropuesta(hayIa, "aceptada");
                return deLaIa(clave, propuesta, hayIa);
            }
            // la IA se paso del tiempo: se le avisa cuanto sumo y se le pide una sola vez que lo corrija
            log.warn("La ruta de la IA suma {} h y el tope es {} h", horas, presupuesto);
            contarPropuesta(hayIa, "excede_horas");
            if (hayIa) {
                try {
                    PropuestaRoadmap corregida = preguntarALaIa(catalogoDisponible, contexto.conCorreccion(
                            "Tu propuesta anterior sumaba " + horas + " horas y el tope es " + presupuesto
                                    + ". Devuelve una ruta nueva que sume " + presupuesto + " horas o menos."), true);
                    if (esPropuestaValida(corregida, catalogoDisponible) && horasDe(corregida, catalogoDisponible) <= presupuesto) {
                        contarPropuesta(true, "corregida");
                        return deLaIa(clave, corregida, true);
                    }
                    contarPropuesta(true, "invalida");
                } catch (RoadmapAiException e) {
                    log.warn("Fallo la IA al corregir el tiempo: {}", e.getMessage());
                }
            }
            log.warn("Se usa el modo de respaldo, que arma la ruta dentro del tiempo");
            return new Generado(fallbackClient.generarRuta(catalogoDisponible, contexto), FuenteRoadmap.RESPALDO);
        }
        log.warn("La respuesta de la IA no es un subconjunto valido del catalogo disponible/prerequisitos reales "
                + "(incluye algo inventado, repetido, dado de baja o ya completado por esta persona), "
                + "se usa el modo de respaldo");
        contarPropuesta(hayIa, "invalida");
        return new Generado(fallbackClient.generarRuta(catalogoDisponible, contexto), FuenteRoadmap.RESPALDO);
    }

    // la llamada a la IA, midiendo como le fue y cuanto tardo. Si el aiClient es el respaldo (sin llave) no es una
    // llamada a la IA y no se cuenta. Lo que lance (fallo, o "la meta no es viable") se vuelve a lanzar tal cual
    private PropuestaRoadmap preguntarALaIa(List<CursoGrafoNodo> catalogo, ContextoRoadmap contexto, boolean hayIa) {
        long inicio = System.nanoTime();
        try {
            PropuestaRoadmap propuesta = aiClient.generarRuta(catalogo, contexto);
            if (hayIa) {
                metricas.llamadaIa("respondio", System.nanoTime() - inicio);
            }
            return propuesta;
        } catch (RoadmapAiException e) {
            if (hayIa) {
                metricas.llamadaIa("fallo", System.nanoTime() - inicio);
            }
            throw e;
        } catch (MetaNoViableException e) {
            if (hayIa) {
                metricas.llamadaIa("no_viable", System.nanoTime() - inicio);
            }
            throw e;
        }
    }

    private void contarPropuesta(boolean hayIa, String resultado) {
        if (hayIa) {
            metricas.propuestaDeLaIa(resultado);
        }
    }

    // una propuesta de la IA que paso la validacion: se guarda (solo las de la IA; lo que sale del respaldo no se
    // guarda, asi cuando la IA vuelva a responder no se queda pegada la respuesta de emergencia)
    private Generado deLaIa(String clave, PropuestaRoadmap propuesta, boolean hayIa) {
        if (hayIa && clave != null) {
            try {
                propuestaCache.guardar(clave, propuesta);
            } catch (DataIntegrityViolationException e) {
                // otra peticion igual la guardo primero: vale lo mismo
            } catch (RuntimeException e) {
                log.warn("No se pudo guardar la propuesta de la IA (el roadmap se entrega igual): {}", e.getMessage());
            }
        }
        return new Generado(propuesta, hayIa ? FuenteRoadmap.IA : FuenteRoadmap.RESPALDO);
    }

    // hash de TODO lo que influye en la respuesta de la IA: si algo cambia (hasta un curso nuevo o editado, o
    // que esta persona complete un curso que antes estaba disponible), cambia la clave
    private String claveDe(ContextoRoadmap contexto, List<CursoGrafoNodo> catalogoDisponible) {
        StringBuilder sb = new StringBuilder("v4|"); // sube al cambiar el prompt: asi no se reutilizan propuestas viejas
        sb.append(TextoUtil.normalizar(contexto.metas()).replaceAll("[^a-z0-9+# ]+", " ").replaceAll("\\s+", " ").trim()).append('|');
        contexto.intereses().stream().map(Enum::name).sorted().forEach(a -> sb.append(a).append(','));
        sb.append('|').append(contexto.nivel()).append('|').append(contexto.presupuestoHoras()).append('|');
        catalogoDisponible.stream().sorted(Comparator.comparing(CursoGrafoNodo::cursoId)).forEach(c -> sb
                .append(c.cursoId()).append(':').append(c.titulo()).append(':').append(c.categoria()).append(':')
                .append(c.nivel()).append(':').append(c.duracionHoras()).append(':')
                .append(c.prerequisitoIds().stream().sorted().toList()).append(':')
                .append(c.habilidades().stream().sorted().toList()).append(':').append(c.descripcion()).append(';'));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 existe en toda JVM
        }
    }

    // lo que suma la ruta propuesta, con las horas reales del catalogo (nunca las que diga la IA)
    private int horasDe(PropuestaRoadmap propuesta, List<CursoGrafoNodo> catalogoDisponible) {
        Map<Long, CursoGrafoNodo> porId = catalogoDisponible.stream()
                .collect(Collectors.toMap(CursoGrafoNodo::cursoId, c -> c));
        return propuesta.ids().stream().map(porId::get).filter(Objects::nonNull)
                .mapToInt(c -> c.duracionHoras() == null ? 0 : c.duracionHoras()).sum();
    }

    // nunca se guarda un roadmap con cursos o relaciones inventadas: todo id debe existir en el catalogo
    // DISPONIBLE (un curso dado de baja no esta ahi, y TAMPOCO uno que esta persona ya completo, ver
    // sinCompletados: eso rechaza de una cualquier propuesta de la IA que incluya un curso ya hecho, sin
    // necesidad de revisarlo aparte), sin repetidos, y cada curso debe aparecer despues de sus prerequisitos
    // (los que tambien esten en la ruta; uno que ya se completo no esta en este catalogo asi que no exige nada)
    private boolean esPropuestaValida(PropuestaRoadmap propuesta, List<CursoGrafoNodo> catalogoDisponible) {
        if (propuesta == null || propuesta.cursos() == null || propuesta.cursos().isEmpty()
                || propuesta.cursos().size() > MAX_CURSOS_EN_RUTA) {
            return false;
        }
        List<Long> orden = propuesta.ids();
        Map<Long, CursoGrafoNodo> porId = catalogoDisponible.stream()
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
    // candidatos = cursos activos existentes de esa misma categoria y de un nivel MENOR al del curso nuevo
    // (un prerequisito de su mismo nivel o de uno mas alto no tiene sentido y abre la puerta a ciclos).
    public SugerenciaPrerequisitosResultado sugerirPrerequisitos(Categoria categoria, Nivel nivel, String titulo,
                                                                   String descripcion) {
        List<Curso> candidatos = cursoRepository.buscarCatalogo(categoria, null).stream()
                .filter(c -> c.getNivel().ordinal() < nivel.ordinal())
                .toList();
        if (candidatos.isEmpty()) {
            // nada que sugerir: ni se gasta una llamada a la IA
            return new SugerenciaPrerequisitosResultado(List.of(), aiClient == fallbackClient);
        }
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
