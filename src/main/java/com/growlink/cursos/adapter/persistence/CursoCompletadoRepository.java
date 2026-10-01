package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.CursoCompletado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CursoCompletadoRepository extends JpaRepository<CursoCompletado, Long> {

    boolean existsByUsuarioIdAndCursoId(Long usuarioId, Long cursoId);

    List<CursoCompletado> findByUsuarioId(Long usuarioId);
}
