package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Roadmap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapRepository extends JpaRepository<Roadmap, Long> {

    Optional<Roadmap> findFirstByUsuarioIdOrderByCreadoEnDesc(Long usuarioId);
}
