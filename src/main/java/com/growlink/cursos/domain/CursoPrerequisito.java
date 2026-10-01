package com.growlink.cursos.domain;

import jakarta.persistence.*;

// una fila dice "cursoId necesita prerequisitoId para poder tomarse"
@Entity
@Table(name = "curso_prerequisito", uniqueConstraints = @UniqueConstraint(columnNames = {"cursoId", "prerequisitoId"}))
public class CursoPrerequisito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cursoId;

    @Column(nullable = false)
    private Long prerequisitoId;

    protected CursoPrerequisito() {
        // JPA
    }

    public CursoPrerequisito(Long cursoId, Long prerequisitoId) {
        this.cursoId = cursoId;
        this.prerequisitoId = prerequisitoId;
    }

    public Long getId() {
        return id;
    }

    public Long getCursoId() {
        return cursoId;
    }

    public Long getPrerequisitoId() {
        return prerequisitoId;
    }
}
