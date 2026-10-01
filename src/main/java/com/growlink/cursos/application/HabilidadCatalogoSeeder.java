package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.HabilidadRepository;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Habilidad;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// Siembra el catalogo cerrado de habilidades la primera vez que arranca el
// servicio (tabla vacia). Es un punto de partida, se puede ampliar despues
// agregando filas directamente, sin tocar codigo.
@Component
public class HabilidadCatalogoSeeder implements CommandLineRunner {

    private static final Map<Categoria, List<String>> CATALOGO = Map.ofEntries(
            Map.entry(Categoria.INGENIERIA_SISTEMAS, List.of(
                    "Programación", "Estructuras de Datos", "Bases de Datos", "Redes",
                    "Sistemas Operativos", "Ingeniería de Software", "Inteligencia Artificial", "Ciberseguridad")),
            Map.entry(Categoria.INGENIERIA_CIVIL, List.of(
                    "Estructuras", "Geotecnia", "Hidráulica", "Materiales de Construcción",
                    "Topografía", "Gestión de Obras")),
            Map.entry(Categoria.INGENIERIA_INDUSTRIAL, List.of(
                    "Gestión de Procesos", "Logística", "Control de Calidad",
                    "Investigación de Operaciones", "Gestión de Producción")),
            Map.entry(Categoria.INGENIERIA_ELECTRONICA, List.of(
                    "Circuitos Eléctricos", "Electrónica Digital", "Sistemas de Control",
                    "Telecomunicaciones", "Microcontroladores")),
            Map.entry(Categoria.INGENIERIA_MECANICA, List.of(
                    "Termodinámica", "Mecánica de Materiales", "Diseño Mecánico",
                    "Manufactura", "Mecánica de Fluidos")),
            Map.entry(Categoria.INGENIERIA_AMBIENTAL, List.of(
                    "Gestión Ambiental", "Tratamiento de Aguas", "Sostenibilidad",
                    "Evaluación de Impacto Ambiental")),
            Map.entry(Categoria.MATEMATICAS, List.of(
                    "Cálculo", "Álgebra Lineal", "Estadística", "Ecuaciones Diferenciales",
                    "Métodos Numéricos")),
            Map.entry(Categoria.ADMINISTRACION_EMPRESAS, List.of(
                    "Finanzas", "Mercadeo", "Gestión Estratégica", "Contabilidad",
                    "Gestión de Proyectos")),
            Map.entry(Categoria.IDIOMAS, List.of(
                    "Inglés", "Francés", "Comprensión Lectora", "Escritura Académica", "Conversación")),
            Map.entry(Categoria.DERECHO, List.of(
                    "Derecho Civil", "Derecho Laboral", "Derecho Constitucional",
                    "Derecho Comercial", "Derechos Humanos"))
    );

    private final HabilidadRepository habilidadRepository;

    public HabilidadCatalogoSeeder(HabilidadRepository habilidadRepository) {
        this.habilidadRepository = habilidadRepository;
    }

    @Override
    public void run(String... args) {
        if (habilidadRepository.count() > 0) {
            return;
        }
        CATALOGO.forEach((categoria, nombres) ->
                nombres.forEach(nombre -> habilidadRepository.save(new Habilidad(nombre, categoria))));
    }
}
