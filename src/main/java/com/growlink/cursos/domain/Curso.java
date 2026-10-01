package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.util.LinkedHashSet;
import java.util.Set;

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

    // catalogo cerrado: curso_habilidad referencia ids de Habilidad, ya no texto libre
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "curso_habilidad",
            joinColumns = @JoinColumn(name = "curso_id"),
            inverseJoinColumns = @JoinColumn(name = "habilidad_id"))
    private Set<Habilidad> habilidades = new LinkedHashSet<>();

    private String linkContenido;

    @Column(nullable = false)
    private boolean activo;

    @Column(nullable = false)
    private Long publicadorUsuarioId;

    protected Curso() {
        // JPA
    }

    public Curso(String titulo, String descripcion, Categoria categoria, Nivel nivel,
                 Set<Habilidad> habilidades, String linkContenido, Long publicadorUsuarioId) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.categoria = categoria;
        this.nivel = nivel;
        this.habilidades = new LinkedHashSet<>(habilidades);
        this.linkContenido = linkContenido;
        this.publicadorUsuarioId = publicadorUsuarioId;
        this.activo = true;
    }

    // HU-08: editar titulo, descripcion, nivel, habilidades y link.
    // La categoria no se edita aqui porque las habilidades estan atadas a ella.
    public void editar(String titulo, String descripcion, Nivel nivel, Set<Habilidad> habilidades,
                        String linkContenido) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.nivel = nivel;
        this.habilidades = new LinkedHashSet<>(habilidades);
        this.linkContenido = linkContenido;
    }

    // HU-09: baja logica, no se borra la fila
    public void darDeBaja() {
        this.activo = false;
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

    public Set<Habilidad> getHabilidades() {
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
