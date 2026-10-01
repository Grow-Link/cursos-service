package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.CursoPrerequisito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CursoPrerequisitoRepository extends JpaRepository<CursoPrerequisito, Long> {

    List<CursoPrerequisito> findByCursoId(Long cursoId);

    @Transactional
    void deleteByCursoId(Long cursoId);
}
