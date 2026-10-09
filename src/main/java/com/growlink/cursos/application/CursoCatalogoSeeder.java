package com.growlink.cursos.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.HabilidadRepository;
import com.growlink.cursos.adapter.persistence.PreguntaExamenRepository;
import com.growlink.cursos.adapter.persistence.RoadmapRepository;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Habilidad;
import com.growlink.cursos.domain.Nivel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

// Siembra el catalogo de demostracion (src/main/resources/catalogo-demo.json): cursos reales con descripcion,
// horas, temario, enlace, prerequisitos y examen. Se puede ampliar editando ese archivo o publicando cursos
// desde la aplicacion.
//
// Es seguro correrlo en cada arranque:
//  - un curso que no existe se crea completo;
//  - un curso viejo con el mismo titulo pero SIN horas (los 11 que se sembraban antes) se completa una sola vez;
//  - un curso que ya tiene horas NO se toca (asi no se pisa lo que un publicador haya editado), solo se le
//    agrega el examen si no tenia ninguno.
// Al final deja un usuario de demostracion con un roadmap y cursos ya aprobados, para mostrar el avance.
@Component
public class CursoCatalogoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CursoCatalogoSeeder.class);

    // usuario sembrado en usuarios-service con este mismo perfil (Esteban Londoño, Desarrollador Junior)
    static final long USUARIO_DEMO_ID = 5L;
    static final String USUARIO_DEMO_META = "Quiero ser desarrollador backend y trabajar con datos usando Python";
    static final int CURSOS_YA_APROBADOS_DEMO = 3;

    record PreguntaDemo(String p, List<String> o, int c) {
    }

    record CursoDemo(String clave, String titulo, Categoria area, Nivel nivel, int horas, long publicador,
                     List<String> prerequisitos, List<String> habilidades, String descripcion,
                     List<String> temario, String link, List<PreguntaDemo> examen) {
    }

    record CatalogoDemo(List<CursoDemo> cursos) {
    }

    private final CursoRepository cursoRepository;
    private final CursoService cursoService;
    private final ExamenService examenService;
    private final RoadmapService roadmapService;
    private final RoadmapRepository roadmapRepository;
    private final PreguntaExamenRepository preguntaRepository;
    private final HabilidadRepository habilidadRepository;
    private final HabilidadCatalogoSeeder habilidadCatalogoSeeder;
    private final ObjectMapper objectMapper;

    public CursoCatalogoSeeder(CursoRepository cursoRepository, CursoService cursoService,
                                ExamenService examenService, RoadmapService roadmapService,
                                RoadmapRepository roadmapRepository, PreguntaExamenRepository preguntaRepository,
                                HabilidadRepository habilidadRepository,
                                HabilidadCatalogoSeeder habilidadCatalogoSeeder, ObjectMapper objectMapper) {
        this.cursoRepository = cursoRepository;
        this.cursoService = cursoService;
        this.examenService = examenService;
        this.roadmapService = roadmapService;
        this.roadmapRepository = roadmapRepository;
        this.preguntaRepository = preguntaRepository;
        this.habilidadRepository = habilidadRepository;
        this.habilidadCatalogoSeeder = habilidadCatalogoSeeder;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        // Spring no garantiza el orden entre CommandLineRunner sin @Order, y este seeder necesita que el
        // catalogo de habilidades ya exista. Llamarlo aqui es seguro: es idempotente
        habilidadCatalogoSeeder.run();

        List<CursoDemo> definiciones = leerCatalogo();
        Map<String, Curso> porTitulo = new HashMap<>();
        for (Curso c : cursoRepository.findAll()) {
            porTitulo.putIfAbsent(c.getTitulo(), c);
        }

        // primera pasada: crear o completar los cursos (los prerequisitos van despues, en la segunda pasada,
        // porque un curso puede depender de otro que aparece mas abajo en el archivo)
        Map<String, Long> idPorClave = new HashMap<>();
        Set<String> aActualizarPrerequisitos = new LinkedHashSet<>();
        for (CursoDemo def : definiciones) {
            List<Long> habilidadIds = habilidadIdsPorNombre(def.area(), def.habilidades());
            List<PreguntaExamenInput> examen = def.examen().stream()
                    .map(q -> new PreguntaExamenInput(q.p(), q.o(), q.c())).toList();
            Curso existente = porTitulo.get(def.titulo());
            if (existente == null) {
                Curso nuevo = cursoService.crear(def.titulo(), def.descripcion(), def.area(), def.nivel(), habilidadIds,
                        def.link(), def.publicador(), List.of(), def.horas(), def.temario(), examen,
                        def.publicador(), true);
                idPorClave.put(def.clave(), nuevo.getId());
                aActualizarPrerequisitos.add(def.clave());
            } else if (existente.getDuracionHoras() == null) {
                cursoService.editar(existente.getId(), def.titulo(), def.descripcion(), def.nivel(), habilidadIds,
                        def.link(), def.horas(), def.temario(), examen, existente.getPublicadorUsuarioId(), true);
                idPorClave.put(def.clave(), existente.getId());
                aActualizarPrerequisitos.add(def.clave());
            } else {
                idPorClave.put(def.clave(), existente.getId());
                if (preguntaRepository.countByCursoId(existente.getId()) == 0) {
                    examenService.reemplazar(existente.getId(), examen);
                }
            }
        }

        // segunda pasada: los prerequisitos reales de los cursos que se acaban de crear o completar
        for (CursoDemo def : definiciones) {
            if (!aActualizarPrerequisitos.contains(def.clave())) {
                continue;
            }
            List<Long> prerequisitoIds = def.prerequisitos().stream().map(idPorClave::get).toList();
            cursoService.actualizarPrerequisitos(idPorClave.get(def.clave()), prerequisitoIds, def.publicador(), true);
        }

        sembrarUsuarioDemo();
    }

    private List<CursoDemo> leerCatalogo() throws Exception {
        try (InputStream in = new ClassPathResource("catalogo-demo.json").getInputStream()) {
            return objectMapper.readValue(in, CatalogoDemo.class).cursos();
        }
    }

    // Un usuario que ya va por la mitad: tiene su roadmap y ya aprobo los primeros cursos, para poder mostrar
    // como avanza el camino sin tener que hacer todos los examenes en vivo. Solo se crea si todavia no tiene roadmap.
    // Usa siempre el modo de respaldo: sembrar datos nunca gasta una llamada a la IA.
    private void sembrarUsuarioDemo() {
        try {
            if (roadmapRepository.findFirstByUsuarioIdOrderByCreadoEnDesc(USUARIO_DEMO_ID).isPresent()) {
                return;
            }
            RoadmapDetalle ruta = roadmapService.generarConRespaldo(USUARIO_DEMO_ID, USUARIO_DEMO_META,
                    List.of(Categoria.INGENIERIA_SISTEMAS), Nivel.PRINCIPIANTE);
            ruta.cursos().stream().limit(CURSOS_YA_APROBADOS_DEMO).forEach(c -> {
                try {
                    cursoService.completar(c.curso().getId(), USUARIO_DEMO_ID);
                } catch (CursoYaCompletadoException e) {
                    // ya estaba, no pasa nada
                }
            });
        } catch (RuntimeException e) {
            log.warn("No se pudo sembrar el usuario de demostracion: {}", e.getMessage());
        }
    }

    private List<Long> habilidadIdsPorNombre(Categoria categoria, List<String> nombres) {
        List<Habilidad> deLaCategoria = habilidadRepository.findByCategoria(categoria);
        return nombres.stream()
                .map(nombre -> deLaCategoria.stream()
                        .filter(h -> h.getNombre().equals(nombre))
                        .findFirst()
                        .map(Habilidad::getId)
                        .orElseThrow(() -> new IllegalStateException(
                                "CursoCatalogoSeeder: no se encontro la habilidad '" + nombre + "' en " + categoria)))
                .collect(Collectors.toList());
    }
}
