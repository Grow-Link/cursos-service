package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.time.Instant;

// Una propuesta de la IA que ya paso la validacion, guardada para no volver a preguntar lo mismo. La clave es un hash
// de TODO lo que influye en la respuesta (la meta, las areas, el nivel, el tiempo y el catalogo activo completo): si
// cualquiera cambia, la clave cambia y se le pregunta a la IA de nuevo. Asi la misma entrada da siempre la misma ruta
// (la IA por si sola no lo garantiza) y, de paso, la respuesta repetida es instantanea y no gasta una llamada.
@Entity
@Table(name = "propuesta_guardada", uniqueConstraints = @UniqueConstraint(columnNames = "clave"))
public class PropuestaGuardada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String clave;

    // [{"id": 3, "razon": "..."}, ...] en el orden de la ruta
    @Column(nullable = false, length = 8000)
    private String cursosJson;

    @Column(length = 800)
    private String resumen;

    @Column(nullable = false)
    private Instant creadoEn;

    protected PropuestaGuardada() {
        // JPA
    }

    public PropuestaGuardada(String clave, String cursosJson, String resumen) {
        this.clave = clave;
        this.cursosJson = cursosJson;
        this.resumen = resumen;
        this.creadoEn = Instant.now();
    }

    public String getCursosJson() {
        return cursosJson;
    }

    public String getResumen() {
        return resumen;
    }
}
