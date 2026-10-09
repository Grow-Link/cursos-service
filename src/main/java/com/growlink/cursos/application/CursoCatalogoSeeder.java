package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.HabilidadRepository;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Habilidad;
import com.growlink.cursos.domain.Nivel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Siembra un catalogo real de cursos (con prerequisitos reales) la primera
// vez que arranca el servicio, igual de "punto de partida" que
// HabilidadCatalogoSeeder: solo corre si la tabla curso esta vacia, y se
// puede seguir ampliando despues agregando filas sin tocar codigo.
//
// Mismos titulos/categorias/niveles/prerequisitos que usa
// GrowLink-FRONTEND/scripts/seed-roadmap-demo.mjs (constante CATALOGO), para
// que los datos de demo del frontend y el catalogo real de este servicio
// queden consistentes.
@Component
public class CursoCatalogoSeeder implements CommandLineRunner {

    // key -> definicion. prerequisitoKeys usa keys de esta misma lista y
    // tiene que ir en orden (los prerequisitos antes que quien los requiere),
    // igual que en el script del frontend.
    private record DefinicionCurso(String key, String titulo, Categoria categoria, Nivel nivel,
                                    Long publicadorUsuarioId, List<String> prerequisitoKeys,
                                    List<String> habilidades, String descripcion, int duracionHoras) {
    }

    // publicadorUsuarioId usa ids de PUBLICADOR ya sembrados en usuarios-service
    // (2, 9, 10, 11, 12, 13), repartidos entre los cursos.
    private static final List<DefinicionCurso> CATALOGO = List.of(
            new DefinicionCurso("mat1", "Matemáticas Discretas", Categoria.MATEMATICAS, Nivel.PRINCIPIANTE,
                    2L, List.of(), List.of("Álgebra Lineal"),
                    "Introduce lógica proposicional, teoría de conjuntos, combinatoria y fundamentos de teoría "
                            + "de grafos, la base matemática detrás de algoritmos y estructuras de datos. Pensado "
                            + "para quienes están empezando una carrera de ingeniería y necesitan ese lenguaje "
                            + "formal antes de cursos más avanzados.",
                    30),
            new DefinicionCurso("sis1", "Fundamentos de Programación", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 9L, List.of(), List.of("Programación"),
                    "Primeros pasos en programación: variables, condicionales, ciclos, funciones y manejo "
                            + "básico de errores, resueltos con ejercicios prácticos. Pensado para quienes nunca "
                            + "programaron y quieren una base sólida antes de entrar a Programación Orientada a "
                            + "Objetos.",
                    24),
            new DefinicionCurso("sis2", "Bases de Datos Relacionales", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 10L, List.of(), List.of("Bases de Datos"),
                    "Modelo relacional, diseño de tablas, normalización y consultas SQL (SELECT, JOIN, "
                            + "agregaciones) sobre una base de datos real. Para quienes van a diseñar o consumir "
                            + "bases de datos en cualquier aplicación, sin experiencia previa necesaria.",
                    20),
            new DefinicionCurso("adm1", "Contabilidad para no Contadores", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.PRINCIPIANTE, 11L, List.of(), List.of("Contabilidad"),
                    "Estados financieros básicos, costos, flujo de caja y los indicadores que cualquier "
                            + "profesional necesita para leer la salud de un negocio. Pensado para perfiles no "
                            + "contables (ingenieros, técnicos, emprendedores) que necesitan tomar decisiones con "
                            + "esos números.",
                    16),
            new DefinicionCurso("sis3", "Programación Orientada a Objetos", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 12L, List.of("sis1"), List.of("Programación", "Ingeniería de Software"),
                    "Clases, objetos, herencia, polimorfismo y encapsulamiento, aplicados a un proyecto pequeño "
                            + "de extremo a extremo. Continúa después de Fundamentos de Programación, para quien "
                            + "ya sabe programar y quiere estructurar código más grande y mantenible.",
                    28),
            new DefinicionCurso("adm2", "Gestión de Proyectos Ágiles", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.INTERMEDIO, 13L, List.of("adm1", "sis1"), List.of("Gestión de Proyectos"),
                    "Scrum y Kanban en la práctica: roles, ceremonias, backlog, estimación y métricas de "
                            + "seguimiento, con un caso de proyecto simulado. Para quienes van a liderar o "
                            + "participar en equipos de proyecto con entregas iterativas, con algo de base de "
                            + "negocio y de desarrollo.",
                    20),
            new DefinicionCurso("sis4", "Estructuras de Datos y Algoritmos", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.INTERMEDIO, 2L, List.of("sis3", "mat1"), List.of("Estructuras de Datos"),
                    "Listas, pilas, colas, árboles, tablas hash y grafos, con análisis de complejidad "
                            + "(notación Big-O) y los algoritmos clásicos de búsqueda y ordenamiento. Requiere "
                            + "POO y matemáticas discretas, y es la base para cualquier entrevista técnica o "
                            + "sistema que necesite rendimiento.",
                    45),
            new DefinicionCurso("sis5", "Desarrollo Web Backend", Categoria.INGENIERIA_SISTEMAS, Nivel.INTERMEDIO,
                    9L, List.of("sis3", "sis2"), List.of("Programación", "Bases de Datos"),
                    "Construcción de APIs REST, autenticación, persistencia con una base relacional y pruebas "
                            + "automatizadas, con un framework moderno del lado del servidor. Pensado para quien "
                            + "ya programa orientado a objetos y maneja SQL, y quiere exponer esa lógica como un "
                            + "servicio real.",
                    40),
            new DefinicionCurso("sis6", "Arquitectura de Software", Categoria.INGENIERIA_SISTEMAS, Nivel.INTERMEDIO,
                    10L, List.of("sis4", "sis5"), List.of("Ingeniería de Software"),
                    "Estilos y patrones arquitectónicos (capas, microservicios, mensajería), atributos de "
                            + "calidad (escalabilidad, mantenibilidad) y cómo documentar y justificar decisiones "
                            + "de diseño. Para quienes ya construyen backends y estructuras de datos y quieren "
                            + "pensar el sistema completo, no solo una funcionalidad.",
                    35),
            new DefinicionCurso("adm3", "Liderazgo de Equipos Técnicos", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.INTERMEDIO, 11L, List.of("adm2", "sis5"), List.of("Gestión Estratégica"),
                    "Comunicación, feedback, manejo de conflictos y toma de decisiones técnicas en equipo, "
                            + "aplicado a equipos de desarrollo de software. Pensado para quien ya gestionó "
                            + "proyectos o programó en equipo y va a dar el salto a liderar personas, no solo "
                            + "tareas.",
                    24),
            new DefinicionCurso("sis7", "Sistemas Distribuidos y Concurrencia", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.AVANZADO, 12L, List.of("sis6"), List.of("Sistemas Operativos", "Redes"),
                    "Concurrencia, paralelismo, consistencia, tolerancia a fallos y comunicación entre nodos, "
                            + "con ejercicios sobre sistemas que corren en más de una máquina. Requiere "
                            + "arquitectura de software, y es para quien va a diseñar o depurar sistemas que "
                            + "escalan horizontalmente.",
                    50)
    );

    private final CursoRepository cursoRepository;
    private final CursoService cursoService;
    private final HabilidadRepository habilidadRepository;
    private final HabilidadCatalogoSeeder habilidadCatalogoSeeder;

    public CursoCatalogoSeeder(CursoRepository cursoRepository, CursoService cursoService,
                                HabilidadRepository habilidadRepository,
                                HabilidadCatalogoSeeder habilidadCatalogoSeeder) {
        this.cursoRepository = cursoRepository;
        this.cursoService = cursoService;
        this.habilidadRepository = habilidadRepository;
        this.habilidadCatalogoSeeder = habilidadCatalogoSeeder;
    }

    @Override
    public void run(String... args) {
        // Spring no garantiza el orden entre CommandLineRunner sin @Order, y
        // este seeder necesita que el catalogo de habilidades ya exista.
        // Llamarlo aqui es seguro: HabilidadCatalogoSeeder ya es idempotente
        // (solo siembra si su tabla esta vacia), asi que no importa si Spring
        // tambien lo invoca por su cuenta despues.
        habilidadCatalogoSeeder.run();

        if (cursoRepository.count() > 0) {
            // ya sembrado antes: no se reinserta el catalogo, pero se
            // completan descripcion/duracionHoras en lo que quedo con el
            // valor de relleno original (ver backfillDatosReales)
            backfillDatosReales();
            return;
        }

        Map<String, Long> idPorKey = new HashMap<>();
        for (DefinicionCurso def : CATALOGO) {
            List<Long> prerequisitoIds = def.prerequisitoKeys().stream().map(idPorKey::get).toList();
            List<Long> habilidadIds = habilidadIdsPorNombre(def.categoria(), def.habilidades());

            Curso curso = cursoService.crear(def.titulo(), def.descripcion(), def.categoria(), def.nivel(),
                    habilidadIds, null, def.publicadorUsuarioId(), prerequisitoIds,
                    def.publicadorUsuarioId(), true, def.duracionHoras());
            idPorKey.put(def.key(), curso.getId());
        }
    }

    // bono (PDF del roadmap): cuando este seeder sembro el catalogo (en un
    // deploy anterior a que existieran descripcion real/duracionHoras),
    // esos cursos quedaron con la descripcion de relleno y sin duracion.
    // Al arrancar, se completan por titulo con los datos reales de arriba,
    // pero solo si siguen "vacios": si un publicador ya edito el curso
    // (cambio la descripcion de relleno o ya le puso duracionHoras), no se
    // toca. Idempotente: en arranques siguientes ya no encuentra nada que
    // actualizar.
    private void backfillDatosReales() {
        for (DefinicionCurso def : CATALOGO) {
            cursoRepository.findByTitulo(def.titulo()).ifPresent(curso -> {
                boolean sinDuracion = curso.getDuracionHoras() == null;
                boolean descripcionDeRelleno = descripcionDeRelleno(def).equals(curso.getDescripcion());
                if (sinDuracion || descripcionDeRelleno) {
                    cursoService.actualizarDatosDeCatalogo(curso.getId(), def.descripcion(), def.duracionHoras());
                }
            });
        }
    }

    // formula de descripcion que usaba este seeder antes de tener texto real
    // por curso; se usa solo para reconocer filas viejas en el backfill.
    private static String descripcionDeRelleno(DefinicionCurso def) {
        return "Curso de catálogo de " + def.categoria() + " (" + def.nivel() + ").";
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
                .toList();
    }
}
