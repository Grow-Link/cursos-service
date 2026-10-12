package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Roadmap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoadmapRepository extends JpaRepository<Roadmap, Long> {

    Optional<Roadmap> findFirstByUsuarioIdOrderByCreadoEnDesc(Long usuarioId);

    // GET /api/roadmap/completados: TODOS los roadmaps de la persona (no solo el ultimo), nunca se borran ni se
    // reemplazan al generar uno nuevo (ver Roadmap y RoadmapService.guardar)
    List<Roadmap> findByUsuarioIdOrderByCreadoEnAsc(Long usuarioId);
}
