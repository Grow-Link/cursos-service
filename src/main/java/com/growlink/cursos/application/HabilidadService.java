package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.HabilidadRepository;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Habilidad;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class HabilidadService {

    private final HabilidadRepository habilidadRepository;

    public HabilidadService(HabilidadRepository habilidadRepository) {
        this.habilidadRepository = habilidadRepository;
    }

    public List<Habilidad> listar(Categoria categoria) {
        return categoria == null ? habilidadRepository.findAll() : habilidadRepository.findByCategoria(categoria);
    }

    // Resuelve los ids a entidades y valida que todas pertenezcan a la
    // categoria del curso al que se van a asociar.
    public Set<Habilidad> resolverParaCategoria(List<Long> habilidadIds, Categoria categoriaCurso) {
        Set<Habilidad> habilidades = new LinkedHashSet<>();
        if (habilidadIds == null) {
            return habilidades;
        }
        for (Long habilidadId : habilidadIds) {
            Habilidad habilidad = habilidadRepository.findById(habilidadId)
                    .orElseThrow(() -> new HabilidadNoEncontradaException(habilidadId));
            if (habilidad.getCategoria() != categoriaCurso) {
                throw new HabilidadCategoriaInvalidaException(habilidadId, habilidad.getCategoria(), categoriaCurso);
            }
            habilidades.add(habilidad);
        }
        return habilidades;
    }
}
