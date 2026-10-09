package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.adapter.persistence.CursoPrerequisitoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CursoService {

    private final CursoRepository cursoRepository;
    private final CursoPrerequisitoRepository prerequisitoRepository;
    private final CursoCompletadoRepository completadoRepository;
    private final HabilidadService habilidadService;

    public CursoService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository,
                         CursoCompletadoRepository completadoRepository, HabilidadService habilidadService) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
        this.completadoRepository = completadoRepository;
        this.habilidadService = habilidadService;
    }

    @Transactional
    public Curso crear(String titulo, String descripcion, Categoria categoria, Nivel nivel,
                        List<Long> habilidadIds, String linkContenido, Long publicadorUsuarioId,
                        List<Long> prerequisitoIds, Long requestingUserId, boolean isAdmin,
                        Integer duracionHoras) {
        if (!isAdmin && !publicadorUsuarioId.equals(requestingUserId)) {
            throw new NoAutorizadoException();
        }
        Set<Habilidad> habilidades = habilidadService.resolverParaCategoria(habilidadIds, categoria);
        Curso curso = cursoRepository.save(new Curso(titulo, descripcion, categoria, nivel,
                habilidades, linkContenido, publicadorUsuarioId, duracionHoras));
        if (prerequisitoIds != null && !prerequisitoIds.isEmpty()) {
            guardarPrerequisitos(curso.getId(), prerequisitoIds);
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

    @Transactional
    public void actualizarPrerequisitos(Long cursoId, List<Long> nuevosPrerequisitoIds,
                                         Long requestingUserId, boolean isAdmin) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        prerequisitoRepository.deleteByCursoId(cursoId);
        guardarPrerequisitos(cursoId, nuevosPrerequisitoIds);
    }

    // HU-08: editar titulo, descripcion, nivel, habilidades, link, duracionHoras
    @Transactional
    public Curso editar(Long cursoId, String titulo, String descripcion, Nivel nivel, List<Long> habilidadIds,
                         String linkContenido, Long requestingUserId, boolean isAdmin, Integer duracionHoras) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        Set<Habilidad> habilidades = habilidadService.resolverParaCategoria(habilidadIds, curso.getCategoria());
        curso.editar(titulo, descripcion, nivel, habilidades, linkContenido, duracionHoras);
        return curso;
    }

    // bono (PDF del roadmap): backfill de CursoCatalogoSeeder, pisa solo
    // descripcion/duracionHoras de un curso del catalogo (ver Curso.actualizarDatosCatalogo).
    @Transactional
    public void actualizarDatosDeCatalogo(Long cursoId, String descripcion, Integer duracionHoras) {
        obtener(cursoId).actualizarDatosCatalogo(descripcion, duracionHoras);
    }

    // HU-09: baja logica, no borra la fila
    @Transactional
    public void darDeBaja(Long cursoId, Long requestingUserId, boolean isAdmin) {
        Curso curso = obtener(cursoId);
        verificarPropietarioOAdmin(curso, requestingUserId, isAdmin);
        curso.darDeBaja();
    }

    // HU-14: marcar un curso como completado por un usuario
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
