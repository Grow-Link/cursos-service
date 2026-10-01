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

    protected Roadmap() {
        // JPA
    }

    public Roadmap(Long usuarioId, String metas, Nivel nivel) {
        this.usuarioId = usuarioId;
        this.metas = metas;
        this.nivel = nivel;
        this.creadoEn = Instant.now();
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
