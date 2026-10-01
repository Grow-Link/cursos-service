package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Nivel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CursoRepository extends JpaRepository<Curso, Long> {

    List<Curso> findByPublicadorUsuarioId(Long publicadorUsuarioId);

    // HU-13: catalogo general, siempre solo activos, con filtros opcionales
    @Query("SELECT c FROM Curso c WHERE c.activo = true "
            + "AND (:categoria IS NULL OR c.categoria = :categoria) "
            + "AND (:nivel IS NULL OR c.nivel = :nivel)")
    List<Curso> buscarCatalogo(@Param("categoria") Categoria categoria, @Param("nivel") Nivel nivel);
}
