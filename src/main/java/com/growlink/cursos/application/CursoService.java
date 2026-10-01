package com.growlink.cursos.application;

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

    public CursoService(CursoRepository cursoRepository, CursoPrerequisitoRepository prerequisitoRepository) {
        this.cursoRepository = cursoRepository;
        this.prerequisitoRepository = prerequisitoRepository;
    }

    @Transactional
    public Curso crear(String titulo, String descripcion, Categoria categoria, Nivel nivel,
                        List<String> habilidades, String linkContenido, Long publicadorUsuarioId,
                        List<Long> prerequisitoIds) {
        Curso curso = cursoRepository.save(new Curso(titulo, descripcion, categoria, nivel,
                habilidades, linkContenido, publicadorUsuarioId));
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

    public List<Long> prerequisitosDe(Long cursoId) {
        return prerequisitoRepository.findByCursoId(cursoId).stream()
                .map(CursoPrerequisito::getPrerequisitoId)
                .toList();
    }

    // HU-08 completa (editar titulo, descripcion, etc.) todavia no esta hecha
    // esto solo cubre la parte de prerequisitos, que es la que necesita la
    // validacion de ciclos y por eso se construyo primero
    @Transactional
    public void actualizarPrerequisitos(Long cursoId, List<Long> nuevosPrerequisitoIds) {
        obtener(cursoId); // valida que el curso exista
        prerequisitoRepository.deleteByCursoId(cursoId);
        guardarPrerequisitos(cursoId, nuevosPrerequisitoIds);
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
