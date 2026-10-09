package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.PreguntaExamen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PreguntaExamenRepository extends JpaRepository<PreguntaExamen, Long> {

    List<PreguntaExamen> findByCursoIdOrderByOrdenAsc(Long cursoId);

    long countByCursoId(Long cursoId);

    // cuantas preguntas tiene el examen de cada curso, de un solo golpe, para no hacer una consulta por curso
    @Query("SELECT p.cursoId, COUNT(p) FROM PreguntaExamen p WHERE p.cursoId IN :cursoIds GROUP BY p.cursoId")
    List<Object[]> contarPorCurso(@Param("cursoIds") Collection<Long> cursoIds);

    @Modifying
    void deleteByCursoId(Long cursoId);
}
