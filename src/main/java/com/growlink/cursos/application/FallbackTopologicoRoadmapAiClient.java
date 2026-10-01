package com.growlink.cursos.application;

import com.growlink.cursos.domain.Nivel;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

// Modo de respaldo mientras no haya CLAUDE_API_KEY configurada (ver README:
// el pago de la API de Claude sigue sin resolverse). Genera un roadmap
// demostrable ya mismo con una ordenacion topologica simple (Kahn) del
// subgrafo de cursos cuya categoria esta en los intereses del usuario y cuyo
// nivel no supera el nivel pedido, usando solo los prerequisitos reales entre
// esos mismos cursos.
@Component
public class FallbackTopologicoRoadmapAiClient implements RoadmapAiClient {

    @Override
    public List<Long> generarOrden(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
        Map<Long, CursoGrafoNodo> porId = catalogoActivo.stream()
                .collect(Collectors.toMap(CursoGrafoNodo::cursoId, c -> c));

        Set<Long> subgrafo = catalogoActivo.stream()
                .filter(c -> contexto.intereses() == null || contexto.intereses().isEmpty()
                        || contexto.intereses().contains(c.categoria()))
                .filter(c -> c.nivel().ordinal() <= contexto.nivel().ordinal())
                .map(CursoGrafoNodo::cursoId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<Long>> dependientes = new LinkedHashMap<>();
        Map<Long, Integer> gradoEntrada = new LinkedHashMap<>();
        for (Long id : subgrafo) {
            dependientes.put(id, new ArrayList<>());
            gradoEntrada.put(id, 0);
        }
        for (Long id : subgrafo) {
            List<Long> prerequisitosEnSubgrafo = porId.get(id).prerequisitoIds().stream()
                    .filter(subgrafo::contains)
                    .toList();
            gradoEntrada.put(id, prerequisitosEnSubgrafo.size());
            for (Long prerequisitoId : prerequisitosEnSubgrafo) {
                dependientes.get(prerequisitoId).add(id);
            }
        }

        Deque<Long> listos = new ArrayDeque<>();
        gradoEntrada.forEach((id, grado) -> {
            if (grado == 0) {
                listos.add(id);
            }
        });

        List<Long> orden = new ArrayList<>();
        while (!listos.isEmpty()) {
            Long actual = listos.poll();
            orden.add(actual);
            for (Long dependiente : dependientes.get(actual)) {
                int nuevoGrado = gradoEntrada.get(dependiente) - 1;
                gradoEntrada.put(dependiente, nuevoGrado);
                if (nuevoGrado == 0) {
                    listos.add(dependiente);
                }
            }
        }
        return orden;
    }

    // Modo de respaldo para sugerir prerequisitos (ver README): entre los
    // candidatos (ya filtrados a la categoria del curso que se esta
    // publicando) sugiere los de menor nivel, que son los que tiene mas
    // sentido tomar antes dentro de esa misma categoria.
    @Override
    public List<Long> sugerirPrerequisitos(List<CursoGrafoNodo> candidatos, SugerenciaPrerequisitosContexto contexto) {
        Optional<Nivel> menorNivel = candidatos.stream()
                .map(CursoGrafoNodo::nivel)
                .min(Comparator.comparingInt(Enum::ordinal));

        if (menorNivel.isEmpty()) {
            return List.of();
        }

        return candidatos.stream()
                .filter(c -> c.nivel() == menorNivel.get())
                .map(CursoGrafoNodo::cursoId)
                .toList();
    }
}
