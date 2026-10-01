package com.growlink.cursos.adapter.persistence;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Habilidad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HabilidadRepository extends JpaRepository<Habilidad, Long> {

    List<Habilidad> findByCategoria(Categoria categoria);

    boolean existsByNombre(String nombre);
}
