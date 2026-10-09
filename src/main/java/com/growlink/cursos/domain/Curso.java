package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    // cuantas horas toma el curso, lo escribe quien lo publica (puede ser null en cursos viejos)
    private Integer duracionHoras;

    // los temas del curso en orden, es lo que el estudiante ve antes de decidir si tomarlo
    // EAGER porque open-in-view esta apagado
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "curso_temario", joinColumns = @JoinColumn(name = "curso_id"))
    @OrderColumn(name = "orden")
    @Column(name = "tema", length = 300)
    private List<String> temario = new ArrayList<>();

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

    // HU-08: editar titulo, descripcion, nivel, habilidades, link, horas y temario.
    // La categoria no se edita aqui porque las habilidades estan atadas a ella.
    public void editar(String titulo, String descripcion, Nivel nivel, Set<Habilidad> habilidades,
                        String linkContenido, Integer duracionHoras, List<String> temario) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.nivel = nivel;
        this.habilidades = new LinkedHashSet<>(habilidades);
        this.linkContenido = linkContenido;
        definirDetalle(duracionHoras, temario);
    }

    public void definirDetalle(Integer duracionHoras, List<String> temario) {
        this.duracionHoras = duracionHoras;
        this.temario = temario == null ? new ArrayList<>() : new ArrayList<>(temario);
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

    public Integer getDuracionHoras() {
        return duracionHoras;
    }

    public List<String> getTemario() {
        return temario;
    }

    public boolean isActivo() {
        return activo;
    }

    public Long getPublicadorUsuarioId() {
        return publicadorUsuarioId;
    }
}
