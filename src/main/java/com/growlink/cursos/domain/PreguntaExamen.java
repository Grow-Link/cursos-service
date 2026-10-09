package com.growlink.cursos.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

// una pregunta del examen de un curso, siempre con 4 opciones y una sola correcta
// el examen es lo que permite marcar un curso como completado: nadie lo completa a la palabra
@Entity
@Table(name = "pregunta_examen")
public class PreguntaExamen {

    public static final int OPCIONES = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cursoId;

    @Column(nullable = false)
    private int orden;

    @Column(nullable = false, length = 500)
    private String enunciado;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pregunta_examen_opcion", joinColumns = @JoinColumn(name = "pregunta_id"))
    @OrderColumn(name = "posicion")
    @Column(name = "opcion", length = 300)
    private List<String> opciones = new ArrayList<>();

    @Column(nullable = false)
    private int respuestaCorrecta;

    protected PreguntaExamen() {
        // JPA
    }

    public PreguntaExamen(Long cursoId, int orden, String enunciado, List<String> opciones, int respuestaCorrecta) {
        this.cursoId = cursoId;
        this.orden = orden;
        this.enunciado = enunciado;
        this.opciones = new ArrayList<>(opciones);
        this.respuestaCorrecta = respuestaCorrecta;
    }

    public Long getId() {
        return id;
    }

    public Long getCursoId() {
        return cursoId;
    }

    public int getOrden() {
        return orden;
    }

    public String getEnunciado() {
        return enunciado;
    }

    public List<String> getOpciones() {
        return opciones;
    }

    public int getRespuestaCorrecta() {
        return respuestaCorrecta;
    }
}
