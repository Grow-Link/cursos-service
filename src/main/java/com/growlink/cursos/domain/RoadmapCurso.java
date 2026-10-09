package com.growlink.cursos.domain;

import jakarta.persistence.*;

// un curso dentro de un roadmap guardado, con su posicion (orden) en el camino sugerido
@Entity
@Table(name = "roadmap_curso")
public class RoadmapCurso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roadmapId;

    @Column(nullable = false)
    private Long cursoId;

    @Column(nullable = false)
    private int orden;

    // por que este curso esta en la ruta de esta persona, en una frase
    @Column(length = 500)
    private String razon;

    protected RoadmapCurso() {
        // JPA
    }

    public RoadmapCurso(Long roadmapId, Long cursoId, int orden, String razon) {
        this.roadmapId = roadmapId;
        this.cursoId = cursoId;
        this.orden = orden;
        this.razon = razon;
    }

    public String getRazon() {
        return razon;
    }

    public Long getId() {
        return id;
    }

    public Long getRoadmapId() {
        return roadmapId;
    }

    public Long getCursoId() {
        return cursoId;
    }

    public int getOrden() {
        return orden;
    }
}
