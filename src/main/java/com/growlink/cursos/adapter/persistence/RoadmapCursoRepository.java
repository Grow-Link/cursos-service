package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.RoadmapCurso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoadmapCursoRepository extends JpaRepository<RoadmapCurso, Long> {

    List<RoadmapCurso> findByRoadmapIdOrderByOrdenAsc(Long roadmapId);

    // GET /api/roadmap/completados: los cursos de VARIOS roadmaps en una sola consulta (nunca una por roadmap)
    List<RoadmapCurso> findByRoadmapIdIn(Collection<Long> roadmapIds);
}
