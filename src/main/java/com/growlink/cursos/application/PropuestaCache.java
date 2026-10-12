package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.PropuestaGuardadaRepository;
import com.growlink.cursos.domain.PropuestaGuardada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Guarda y recupera las propuestas de la IA ya validadas (ver PropuestaGuardada). Se puede apagar con
// roadmap.ia.cache=false, por ejemplo para medir cuanto varia la IA por si sola.
// guardar() corre en su PROPIA transaccion: si dos peticiones iguales llegan a la vez y chocan con la clave unica,
// ese choque no debe tumbar la transaccion de quien genero el roadmap (ya hay una propuesta guardada y vale igual).
@Component
public class PropuestaCache {

    private static final Logger log = LoggerFactory.getLogger(PropuestaCache.class);

    private final PropuestaGuardadaRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean activa;

    public PropuestaCache(PropuestaGuardadaRepository repository, ObjectMapper objectMapper,
                          @Value("${roadmap.ia.cache:true}") boolean activa) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.activa = activa;
    }

    public Optional<PropuestaRoadmap> buscar(String clave) {
        if (!activa) {
            return Optional.empty();
        }
        return repository.findByClave(clave).flatMap(g -> leer(g));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void guardar(String clave, PropuestaRoadmap propuesta) {
        if (!activa || repository.findByClave(clave).isPresent()) {
            return;
        }
        try {
            List<Map<String, Object>> cursos = new ArrayList<>();
            for (CursoElegido c : propuesta.cursos()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.cursoId());
                m.put("razon", c.razon() == null ? "" : c.razon());
                cursos.add(m);
            }
            String json = objectMapper.writeValueAsString(cursos);
            if (json.length() > 8000) {
                return; // demasiado largo para guardar: no se guarda y listo
            }
            repository.saveAndFlush(new PropuestaGuardada(clave, json, propuesta.resumen()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("No se pudo guardar la propuesta de la IA: {}", e.getMessage());
        }
    }

    private Optional<PropuestaRoadmap> leer(PropuestaGuardada guardada) {
        try {
            List<CursoElegido> cursos = new ArrayList<>();
            for (JsonNode nodo : objectMapper.readTree(guardada.getCursosJson())) {
                cursos.add(new CursoElegido(nodo.path("id").asLong(), nodo.path("razon").asText("")));
            }
            return cursos.isEmpty() ? Optional.empty() : Optional.of(new PropuestaRoadmap(cursos, guardada.getResumen()));
        } catch (Exception e) {
            log.warn("Una propuesta guardada no se pudo leer, se vuelve a preguntar a la IA: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
