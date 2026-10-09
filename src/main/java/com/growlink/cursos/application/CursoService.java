package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.adapter.persistence.CursoPrerequisitoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.PreguntaExamenRepository;
import com.growlink.cursos.adapter.web.dto.AreaResumenResponse;
import com.growlink.cursos.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CursoService {

    public static final int MAX_HORAS = 500;
    public static final int MAX_TEMAS = 20;

    private final CursoRepository cursoRepository;
    private final CursoPrerequisitoRepository prerequisitoRepository;
    private final CursoCompletadoRepository completadoRepository;
    private final PreguntaExamenRepository preguntaExamenRepository;
    private final HabilidadService habilidadService;
    private final ExamenService examenService;

    public CursoService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository,
                         CursoCompletadoRepository completadoRepository,
                         PreguntaExamenRepository preguntaExamenRepository, HabilidadService habilidadService,
                         ExamenService examenService) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
        this.completadoRepository = completadoRepository;
        this.preguntaExamenRepository = preguntaExamenRepository;
        this.habilidadService = habilidadService;
        this.examenService = examenService;
    }

    // examen puede ir vacio (null): el curso queda publicado pero nadie puede completarlo hasta que tenga uno.
    // Si viene, tiene que cumplir las reglas del examen (3 a 15 preguntas de 4 opciones)
    @Transactional
    public Curso crear(String titulo, String descripcion, Categoria categoria, Nivel nivel,
                        List<Long> habilidadIds, String linkContenido, Long publicadorUsuarioId,
                        List<Long> prerequisitoIds, Integer duracionHoras, List<String> temario,
                        List<PreguntaExamenInput> examen, Long requestingUserId, boolean isAdmin) {
        if (!isAdmin && !publicadorUsuarioId.equals(requestingUserId)) {
            throw new NoAutorizadoException();
        }
        validarDetalle(duracionHoras, temario);
        if (examen != null && !examen.isEmpty()) {
            examenService.validar(examen);
        }
        Set<Habilidad> habilidades = habilidadService.resolverParaCategoria(habilidadIds, categoria);
        Curso curso = new Curso(titulo, descripcion, categoria, nivel, habilidades, linkContenido,
                publicadorUsuarioId);
        curso.definirDetalle(duracionHoras, limpiarTemario(temario));
        curso = cursoRepository.save(curso);
        if (prerequisitoIds != null && !prerequisitoIds.isEmpty()) {
            guardarPrerequisitos(curso.getId(), prerequisitoIds);
        }
        if (examen != null && !examen.isEmpty()) {
            examenService.reemplazar(curso.getId(), examen);
        }
        return curso;
    }

    public Curso obtener(Long cursoId) {
        return cursoRepository.findById(cursoId).orElseThrow(() -> new CursoNoEncontradoException(cursoId));
    }

    public List<Curso> listarPorPublicador(Long publicadorUsuarioId) {
        return cursoRepository.findByPublicadorUsuarioId(publicadorUsuarioId);
    }

    // HU-13: catalogo general, siempre solo cursos activos
    public List<Curso> catalogo(Categoria categoria, Nivel nivel) {
        return cursoRepository.buscarCatalogo(categoria, nivel);
    }

    // las areas que de verdad tienen cursos activos ahora mismo, con cuantos hay, de que niveles, cuantas horas
    // suman y ejemplos. Es lo que se le muestra a la persona antes de pedirle su meta; nada va escrito a mano
    public List<AreaResumenResponse> resumenAreas() {
        Map<Categoria, List<Curso>> porArea = cursoRepository.buscarCatalogo(null, null).stream()
                .collect(Collectors.groupingBy(Curso::getCategoria, () -> new EnumMap<>(Categoria.class), Collectors.toList()));
        List<AreaResumenResponse> resumen = new ArrayList<>();
        porArea.forEach((area, cursos) -> {
            Map<String, Long> habilidades = cursos.stream().flatMap(c -> c.getHabilidades().stream())
                    .collect(Collectors.groupingBy(Habilidad::getNombre, Collectors.counting()));
            List<String> topHabilidades = habilidades.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(6).map(Map.Entry::getKey).toList();
            List<String> ejemplos = cursos.stream()
                    .sorted(Comparator.comparing(Curso::getNivel).thenComparing(Curso::getTitulo))
                    .limit(3).map(Curso::getTitulo).toList();
            resumen.add(new AreaResumenResponse(area, area.etiqueta(), cursos.size(),
                    cursos.stream().mapToInt(c -> c.getDuracionHoras() == null ? 0 : c.getDuracionHoras()).sum(),
                    cursos.stream().filter(c -> c.getNivel() == Nivel.PRINCIPIANTE).count(),
                    cursos.stream().filter(c -> c.getNivel() == Nivel.INTERMEDIO).count(),
                    cursos.stream().filter(c -> c.getNivel() == Nivel.AVANZADO).count(),
                    topHabilidades, ejemplos));
        });
        resumen.sort(Comparator.comparingLong(AreaResumenResponse::cursos).reversed());
        return resumen;
    }

    // HU-10: de una lista de ids de un roadmap guardado, cuales ya no estan activos
    public List<Long> idsInactivosDe(List<Long> cursoIds) {
        return cursoRepository.findAllById(cursoIds).stream()
                .filter(c -> !c.isActivo())
                .map(Curso::getId)
                .toList();
    }

    public List<Long> prerequisitosDe(Long cursoId) {
        return prerequisitoRepository.findByCursoId(cursoId).stream()
                .map(CursoPrerequisito::getPrerequisitoId)
                .toList();
    }

    // cuantas preguntas tiene el examen de cada curso (0 = todavia no tiene examen), en una sola consulta
    public Map<Long, Long> totalPreguntasPorCurso(Collection<Long> cursoIds) {
        Map<Long, Long> totales = new HashMap<>();
        if (cursoIds.isEmpty()) {
            return totales;
        }
        for (Object[] fila : preguntaExamenRepository.contarPorCurso(cursoIds)) {
            totales.put((Long) fila[0], (Long) fila[1]);
        }
        return totales;
    }

    @Transactional
    public void actualizarPrerequisitos(Long cursoId, List<Long> nuevosPrerequisitoIds,
                                         Long requestingUserId, boolean isAdmin) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        prerequisitoRepository.deleteByCursoId(cursoId);
        guardarPrerequisitos(cursoId, nuevosPrerequisitoIds);
    }

    // HU-08: editar titulo, descripcion, nivel, habilidades, link, horas y temario. Si viene examen (no null) se
    // reemplaza el examen completo; si no viene, el examen que ya tenia se queda igual
    @Transactional
    public Curso editar(Long cursoId, String titulo, String descripcion, Nivel nivel, List<Long> habilidadIds,
                         String linkContenido, Integer duracionHoras, List<String> temario,
                         List<PreguntaExamenInput> examen, Long requestingUserId, boolean isAdmin) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        validarDetalle(duracionHoras, temario);
        Set<Habilidad> habilidades = habilidadService.resolverParaCategoria(habilidadIds, curso.getCategoria());
        curso.editar(titulo, descripcion, nivel, habilidades, linkContenido, duracionHoras, limpiarTemario(temario));
        if (examen != null) {
            examenService.reemplazar(cursoId, examen);
        }
        return curso;
    }

    // HU-09: baja logica, no borra la fila
    @Transactional
    public void darDeBaja(Long cursoId, Long requestingUserId, boolean isAdmin) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        curso.darDeBaja();
    }

    // HU-14: marcar un curso como completado a la fuerza. Solo lo usa un ADMIN (por ejemplo para sembrar datos de
    // demostracion): una persona completa un curso aprobando su examen (ver ExamenService.presentar)
    @Transactional
    public void completar(Long cursoId, Long usuarioId) {
        obtener(cursoId); // valida que el curso exista (activo o no)
        if (completadoRepository.existsByUsuarioIdAndCursoId(usuarioId, cursoId)) {
            throw new CursoYaCompletadoException(usuarioId, cursoId);
        }
        completadoRepository.save(new CursoCompletado(usuarioId, cursoId));
    }

    // HU-15: historial de completados, incluye cursos ya inactivos
    public List<CompletadoDetalle> completados(Long usuarioId) {
        return completadoRepository.findByUsuarioId(usuarioId).stream()
                .map(cc -> new CompletadoDetalle(obtener(cc.getCursoId()), cc.getFecha()))
                .toList();
    }

    private void validarDetalle(Integer duracionHoras, List<String> temario) {
        if (duracionHoras != null && (duracionHoras < 1 || duracionHoras > MAX_HORAS)) {
            throw new ExamenInvalidoException("La duración debe estar entre 1 y " + MAX_HORAS + " horas.");
        }
        if (temario != null && temario.size() > MAX_TEMAS) {
            throw new ExamenInvalidoException("El temario puede tener máximo " + MAX_TEMAS + " temas.");
        }
    }

    private List<String> limpiarTemario(List<String> temario) {
        if (temario == null) {
            return List.of();
        }
        return temario.stream().filter(Objects::nonNull).map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> t.length() > 300 ? t.substring(0, 300) : t).toList();
    }

    private void verificarPropietarioOAdmin(Curso curso, Long requestingUserId, boolean isAdmin) {
        if (!isAdmin && !curso.getPublicadorUsuarioId().equals(requestingUserId)) {
            throw new NoAutorizadoException();
        }
    }

    private void guardarPrerequisitos(Long cursoId, List<Long> prerequisitoIds) {
        for (Long prerequisitoId : prerequisitoIds) {
            if (!cursoRepository.existsById(prerequisitoId)) {
                throw new CursoNoEncontradoException(prerequisitoId);
            }
            if (generariaCiclo(cursoId, prerequisitoId)) {
                throw new CicloDePrerequisitosException(cursoId, prerequisitoId);
            }
            prerequisitoRepository.save(new CursoPrerequisito(cursoId, prerequisitoId));
        }
    }

    // pregunta: si agrego "cursoId necesita prerequisitoId", se cierra un ciclo
    // eso pasa si prerequisitoId ya llega a cursoId siguiendo su propia cadena
    // de prerequisitos (osea, si prerequisitoId ya depende de cursoId)
    private boolean generariaCiclo(Long cursoId, Long prerequisitoId) {
        if (cursoId.equals(prerequisitoId)) {
            return true; // un curso no puede ser prerequisito de si mismo
        }
        Set<Long> visitados = new HashSet<>();
        Deque<Long> porVisitar = new ArrayDeque<>();
        porVisitar.push(prerequisitoId);

        while (!porVisitar.isEmpty()) {
            Long actual = porVisitar.pop();
            if (actual.equals(cursoId)) {
                return true;
            }
            if (!visitados.add(actual)) {
                continue;
            }
            for (CursoPrerequisito cp : prerequisitoRepository.findByCursoId(actual)) {
                porVisitar.push(cp.getPrerequisitoId());
            }
        }
        return false;
    }
}
