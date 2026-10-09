package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Nivel;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

// Modo de respaldo cuando no hay CLAUDE_API_KEY (o la IA fallo). No es IA: es un algoritmo, pero ya NO ignora
// la meta. Compara las palabras de la meta con el titulo, las habilidades, la descripcion y el temario de cada
// curso de las areas que la persona eligio, se queda con los que coinciden, les suma los prerequisitos que
// necesitan y los ordena con Kahn (ordenacion topologica) usando los prerequisitos reales.
// Si ningun curso coincide con la meta, no inventa una ruta: dice que no puede (MetaNoViableException).
@Component
public class FallbackTopologicoRoadmapAiClient implements RoadmapAiClient {

    // maximo de cursos que coinciden con la meta; con sus prerequisitos la ruta queda en unos 6 a 10
    private static final int MAX_CURSOS_POR_META = 5;

    @Override
    public PropuestaRoadmap generarRuta(List<CursoGrafoNodo> catalogoActivo, ContextoRoadmap contexto) {
        Map<Long, CursoGrafoNodo> porId = catalogoActivo.stream()
                .collect(Collectors.toMap(CursoGrafoNodo::cursoId, c -> c));
        Set<Categoria> areas = contexto.intereses() == null ? Set.of() : new HashSet<>(contexto.intereses());
        List<String> palabrasMeta = TextoUtil.palabrasClave(contexto.metas());

        // 1) que cursos de sus areas coinciden con lo que escribio
        Map<Long, Puntaje> puntajes = new LinkedHashMap<>();
        for (CursoGrafoNodo curso : catalogoActivo) {
            if (!areas.isEmpty() && !areas.contains(curso.categoria())) {
                continue;
            }
            Puntaje p = puntuar(curso, palabrasMeta);
            if (p.valor > 0) {
                puntajes.put(curso.cursoId(), p);
            }
        }
        if (puntajes.isEmpty()) {
            throw new MetaNoViableException("No encontramos cursos relacionados con tu meta en "
                    + (areas.isEmpty() ? "la plataforma" : "las áreas que elegiste")
                    + ". Prueba con otras palabras o elige otra área de las que aparecen disponibles.");
        }

        List<Long> elegidos = puntajes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, Puntaje> e) -> -e.getValue().valor)
                        .thenComparingInt(e -> porId.get(e.getKey()).nivel().ordinal()))
                .limit(MAX_CURSOS_POR_META)
                .map(Map.Entry::getKey)
                .toList();

        // 2) los prerequisitos que esos cursos necesitan (los que son de un nivel menor al de la persona se
        // dan por sabidos y no se le ponen en la ruta)
        Map<Long, Long> necesitadoPor = new LinkedHashMap<>(); // prerequisito -> curso que lo necesita
        Deque<Long> porRevisar = new ArrayDeque<>(elegidos);
        Set<Long> enRuta = new LinkedHashSet<>(elegidos);
        while (!porRevisar.isEmpty()) {
            Long actual = porRevisar.poll();
            for (Long prerequisito : porId.get(actual).prerequisitoIds()) {
                CursoGrafoNodo nodo = porId.get(prerequisito);
                if (nodo == null || enRuta.contains(prerequisito) || nodo.nivel().ordinal() < contexto.nivel().ordinal()) {
                    continue;
                }
                enRuta.add(prerequisito);
                necesitadoPor.put(prerequisito, actual);
                porRevisar.add(prerequisito);
            }
        }

        // 3) orden: primero lo que es base de otros, y entre iguales lo mas basico
        List<Long> orden = ordenTopologico(enRuta, porId, puntajes);

        List<CursoElegido> cursos = new ArrayList<>();
        for (Long id : orden) {
            Puntaje p = puntajes.get(id);
            String razon;
            if (p != null && !p.coincidencias.isEmpty()) {
                razon = "Coincide con tu meta (" + String.join(", ", p.coincidencias) + ").";
            } else {
                razon = "Es la base que necesitas para «" + porId.get(necesitadoPor.get(id)).titulo() + "».";
            }
            cursos.add(new CursoElegido(id, razon));
        }

        int horas = orden.stream().map(porId::get).mapToInt(c -> c.duracionHoras() == null ? 0 : c.duracionHoras()).sum();
        String resumen = "Ruta de " + orden.size() + " cursos" + (horas > 0 ? " (unas " + horas + " horas)" : "")
                + " armada con los cursos de la plataforma que coinciden con tu meta, ordenados para que cada uno "
                + "te prepare para el siguiente. Empieza por «" + porId.get(orden.get(0)).titulo() + "».";
        return new PropuestaRoadmap(cursos, resumen);
    }

    private static class Puntaje {
        int valor;
        final List<String> coincidencias = new ArrayList<>();
    }

    private Puntaje puntuar(CursoGrafoNodo curso, List<String> palabrasMeta) {
        String titulo = TextoUtil.normalizar(curso.titulo());
        String habilidades = TextoUtil.normalizar(String.join(" ", curso.habilidades()));
        String descripcion = TextoUtil.normalizar(curso.descripcion());
        String temario = TextoUtil.normalizar(String.join(" ", curso.temario()));

        Puntaje p = new Puntaje();
        for (String palabra : palabrasMeta) {
            String raiz = TextoUtil.raiz(palabra);
            int subtotal = 0;
            if (titulo.contains(raiz)) subtotal += 4;
            if (habilidades.contains(raiz)) subtotal += 3;
            if (descripcion.contains(raiz)) subtotal += 1;
            if (temario.contains(raiz)) subtotal += 1;
            if (subtotal > 0) {
                p.valor += subtotal;
                p.coincidencias.add(palabra);
            }
        }
        return p;
    }

    private List<Long> ordenTopologico(Set<Long> ids, Map<Long, CursoGrafoNodo> porId, Map<Long, Puntaje> puntajes) {
        Map<Long, List<Long>> dependientes = new HashMap<>();
        Map<Long, Integer> gradoEntrada = new HashMap<>();
        for (Long id : ids) {
            dependientes.put(id, new ArrayList<>());
            gradoEntrada.put(id, 0);
        }
        for (Long id : ids) {
            for (Long prerequisito : porId.get(id).prerequisitoIds()) {
                if (ids.contains(prerequisito)) {
                    gradoEntrada.merge(id, 1, Integer::sum);
                    dependientes.get(prerequisito).add(id);
                }
            }
        }

        Comparator<Long> masBasicoPrimero = Comparator
                .comparingInt((Long id) -> porId.get(id).nivel().ordinal())
                .thenComparingInt(id -> -(puntajes.containsKey(id) ? puntajes.get(id).valor : 0))
                .thenComparingLong(id -> id);
        PriorityQueue<Long> listos = new PriorityQueue<>(masBasicoPrimero);
        gradoEntrada.forEach((id, grado) -> {
            if (grado == 0) listos.add(id);
        });

        List<Long> orden = new ArrayList<>();
        while (!listos.isEmpty()) {
            Long actual = listos.poll();
            orden.add(actual);
            for (Long dependiente : dependientes.get(actual)) {
                if (gradoEntrada.merge(dependiente, -1, Integer::sum) == 0) {
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
