package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.RoadmapCurso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapCursoRepository extends JpaRepository<RoadmapCurso, Long> {

    List<RoadmapCurso> findByRoadmapIdOrderByOrdenAsc(Long roadmapId);
}
