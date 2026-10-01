package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.time.Instant;

// HU-14/15: registro de que un usuario completo un curso. No se borra ni se
// toca si el curso despues se da de baja (ver Curso.darDeBaja): como esa baja
// es logica, el curso y sus habilidades siguen existiendo en la BD, asi que
// el historial de completados puede seguir mostrandolas sin duplicar nada aqui.
@Entity
@Table(name = "curso_completado", uniqueConstraints = @UniqueConstraint(columnNames = {"usuarioId", "cursoId"}))
public class CursoCompletado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usuarioId;

    @Column(nullable = false)
    private Long cursoId;

    @Column(nullable = false)
    private Instant fecha;

    protected CursoCompletado() {
        // JPA
    }

    public CursoCompletado(Long usuarioId, Long cursoId) {
        this.usuarioId = usuarioId;
        this.cursoId = cursoId;
        this.fecha = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public Long getCursoId() {
        return cursoId;
    }

    public Instant getFecha() {
        return fecha;
    }
}
