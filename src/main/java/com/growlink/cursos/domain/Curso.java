package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "curso")
public class Curso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String titulo;

    @Column(length = 1000)
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Categoria categoria;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Nivel nivel;

    // por ahora es texto libre, no un catalogo cerrado por categoria
    // eso queda pendiente, se anota en el README
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "curso_habilidad", joinColumns = @JoinColumn(name = "curso_id"))
    @Column(name = "habilidad")
    private List<String> habilidades = new ArrayList<>();

    private String linkContenido;

    @Column(nullable = false)
    private boolean activo;

    @Column(nullable = false)
    private Long publicadorUsuarioId;

    protected Curso() {
        // JPA
    }

    public Curso(String titulo, String descripcion, Categoria categoria, Nivel nivel,
                 List<String> habilidades, String linkContenido, Long publicadorUsuarioId) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.categoria = categoria;
        this.nivel = nivel;
        this.habilidades = new ArrayList<>(habilidades);
        this.linkContenido = linkContenido;
        this.publicadorUsuarioId = publicadorUsuarioId;
        this.activo = true;
    }

    public Long getId() {
        return id;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public Nivel getNivel() {
        return nivel;
    }

    public List<String> getHabilidades() {
        return habilidades;
    }

    public String getLinkContenido() {
        return linkContenido;
    }

    public boolean isActivo() {
        return activo;
    }

    public Long getPublicadorUsuarioId() {
        return publicadorUsuarioId;
    }
}
