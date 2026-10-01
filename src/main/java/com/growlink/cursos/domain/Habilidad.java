package com.growlink.cursos.domain;

import jakarta.persistence.*;

// catalogo cerrado de habilidades, cada una pertenece a una sola categoria
// (no texto libre, eso era lo pendiente que marcaba el README)
@Entity
@Table(name = "habilidad", uniqueConstraints = @UniqueConstraint(columnNames = {"nombre"}))
public class Habilidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Categoria categoria;

    protected Habilidad() {
        // JPA
    }

    public Habilidad(String nombre, Categoria categoria) {
        this.nombre = nombre;
        this.categoria = categoria;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public Categoria getCategoria() {
        return categoria;
    }
}
