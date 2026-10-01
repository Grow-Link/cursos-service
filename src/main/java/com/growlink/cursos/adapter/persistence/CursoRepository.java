package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Curso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CursoRepository extends JpaRepository<Curso, Long> {

    List<Curso> findByPublicadorUsuarioId(Long publicadorUsuarioId);
}
