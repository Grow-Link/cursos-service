package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.time.Instant;

// HU-11/12: roadmap generado para un usuario (por IA real, o por el modo de
// respaldo con ordenacion topologica mientras no haya API key configurada).
// Guarda solo el ultimo roadmap por usuario no se asume aqui: se permite mas
// de uno en el tiempo y "mio" trae el mas reciente (ver RoadmapRepository).
@Entity
@Table(name = "roadmap")
public class Roadmap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usuarioId;

    @Column(length = 2000)
    private String metas;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Nivel nivel;

    @Column(nullable = false)
    private Instant creadoEn;

    // puede ser null en roadmaps viejos, de antes de que se guardara este dato
    @Enumerated(EnumType.STRING)
    private FuenteRoadmap generadoPor;

    // una o dos frases que resumen la ruta para esta persona (la escribe la IA, o el respaldo)
    @Column(length = 800)
    private String resumen;

    // las areas con las que se genero, separadas por coma (ej. INGENIERIA_SISTEMAS,MATEMATICAS)
    @Column(length = 300)
    private String intereses;

    protected Roadmap() {
        // JPA
    }

    public Roadmap(Long usuarioId, String metas, Nivel nivel, FuenteRoadmap generadoPor, String resumen,
                   String intereses) {
        this.usuarioId = usuarioId;
        this.metas = metas;
        this.nivel = nivel;
        this.creadoEn = Instant.now();
        this.generadoPor = generadoPor;
        this.resumen = resumen;
        this.intereses = intereses;
    }

    public String getResumen() {
        return resumen;
    }

    public String getIntereses() {
        return intereses;
    }

    public FuenteRoadmap getGeneradoPor() {
        return generadoPor;
    }

    public Long getId() {
        return id;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public String getMetas() {
        return metas;
    }

    public Nivel getNivel() {
        return nivel;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
