package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.PropuestaGuardada;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PropuestaGuardadaRepository extends JpaRepository<PropuestaGuardada, Long> {

    Optional<PropuestaGuardada> findByClave(String clave);
}
