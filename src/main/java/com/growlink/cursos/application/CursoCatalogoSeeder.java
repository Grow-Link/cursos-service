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
                                    List<String> habilidades) {
    }

    // publicadorUsuarioId usa ids de PUBLICADOR ya sembrados en usuarios-service
    // (2, 9, 10, 11, 12, 13), repartidos entre los cursos.
    private static final List<DefinicionCurso> CATALOGO = List.of(
            new DefinicionCurso("mat1", "Matemáticas Discretas", Categoria.MATEMATICAS, Nivel.PRINCIPIANTE,
                    2L, List.of(), List.of("Álgebra Lineal")),
            new DefinicionCurso("sis1", "Fundamentos de Programación", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 9L, List.of(), List.of("Programación")),
            new DefinicionCurso("sis2", "Bases de Datos Relacionales", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 10L, List.of(), List.of("Bases de Datos")),
            new DefinicionCurso("adm1", "Contabilidad para no Contadores", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.PRINCIPIANTE, 11L, List.of(), List.of("Contabilidad")),
            new DefinicionCurso("sis3", "Programación Orientada a Objetos", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.PRINCIPIANTE, 12L, List.of("sis1"), List.of("Programación", "Ingeniería de Software")),
            new DefinicionCurso("adm2", "Gestión de Proyectos Ágiles", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.INTERMEDIO, 13L, List.of("adm1", "sis1"), List.of("Gestión de Proyectos")),
            new DefinicionCurso("sis4", "Estructuras de Datos y Algoritmos", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.INTERMEDIO, 2L, List.of("sis3", "mat1"), List.of("Estructuras de Datos")),
            new DefinicionCurso("sis5", "Desarrollo Web Backend", Categoria.INGENIERIA_SISTEMAS, Nivel.INTERMEDIO,
                    9L, List.of("sis3", "sis2"), List.of("Programación", "Bases de Datos")),
            new DefinicionCurso("sis6", "Arquitectura de Software", Categoria.INGENIERIA_SISTEMAS, Nivel.INTERMEDIO,
                    10L, List.of("sis4", "sis5"), List.of("Ingeniería de Software")),
            new DefinicionCurso("adm3", "Liderazgo de Equipos Técnicos", Categoria.ADMINISTRACION_EMPRESAS,
                    Nivel.INTERMEDIO, 11L, List.of("adm2", "sis5"), List.of("Gestión Estratégica")),
            new DefinicionCurso("sis7", "Sistemas Distribuidos y Concurrencia", Categoria.INGENIERIA_SISTEMAS,
                    Nivel.AVANZADO, 12L, List.of("sis6"), List.of("Sistemas Operativos", "Redes"))
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
            return;
        }

        Map<String, Long> idPorKey = new HashMap<>();
        for (DefinicionCurso def : CATALOGO) {
            List<Long> prerequisitoIds = def.prerequisitoKeys().stream().map(idPorKey::get).toList();
            List<Long> habilidadIds = habilidadIdsPorNombre(def.categoria(), def.habilidades());
            String descripcion = "Curso de catálogo de " + def.categoria() + " (" + def.nivel() + ").";

            Curso curso = cursoService.crear(def.titulo(), descripcion, def.categoria(), def.nivel(),
                    habilidadIds, null, def.publicadorUsuarioId(), prerequisitoIds,
                    def.publicadorUsuarioId(), true);
            idPorKey.put(def.key(), curso.getId());
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
                .toList();
    }
}
