package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.CursoPrerequisito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface CursoPrerequisitoRepository extends JpaRepository<CursoPrerequisito, Long> {

    List<CursoPrerequisito> findByCursoId(Long cursoId);

    // los prerequisitos de VARIOS cursos en una sola consulta (nunca una por curso): lo usan el listado del
    // catalogo y la ruta, que arman un DTO por curso
    List<CursoPrerequisito> findByCursoIdIn(Collection<Long> cursoIds);

    @Transactional
    void deleteByCursoId(Long cursoId);
}
