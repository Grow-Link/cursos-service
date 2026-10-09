package com.growlink.cursos.application;

import com.growlink.cursos.adapter.persistence.CursoCompletadoRepository;
import com.growlink.cursos.adapter.persistence.CursoRepository;
import com.growlink.cursos.adapter.persistence.PreguntaExamenRepository;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.CursoCompletado;
import com.growlink.cursos.domain.PreguntaExamen;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// El examen es la unica forma de que una persona complete un curso: se califica aqui, en el servidor.
// El navegador nunca recibe las respuestas correctas.
@Service
public class ExamenService {

    public static final int MINIMO_APROBACION = 70;
    public static final int MIN_PREGUNTAS = 3;
    public static final int MAX_PREGUNTAS = 15;

    private final PreguntaExamenRepository preguntaRepository;
    private final CursoCompletadoRepository completadoRepository;
    private final CursoRepository cursoRepository;

    public ExamenService(PreguntaExamenRepository preguntaRepository, CursoCompletadoRepository completadoRepository,
                         CursoRepository cursoRepository) {
        this.preguntaRepository = preguntaRepository;
        this.completadoRepository = completadoRepository;
        this.cursoRepository = cursoRepository;
    }

    public List<PreguntaExamen> preguntasDe(Long cursoId) {
        return preguntaRepository.findByCursoIdOrderByOrdenAsc(cursoId);
    }

    public boolean yaCompleto(Long usuarioId, Long cursoId) {
        return completadoRepository.existsByUsuarioIdAndCursoId(usuarioId, cursoId);
    }

    // reglas del examen que escribe un publicador: entre 3 y 15 preguntas, cada una con 4 opciones distintas
    // y una correcta
    public void validar(List<PreguntaExamenInput> preguntas) {
        if (preguntas == null || preguntas.size() < MIN_PREGUNTAS || preguntas.size() > MAX_PREGUNTAS) {
            throw new ExamenInvalidoException("El examen debe tener entre " + MIN_PREGUNTAS + " y " + MAX_PREGUNTAS
                    + " preguntas.");
        }
        int numero = 1;
        for (PreguntaExamenInput p : preguntas) {
            String ubicacion = "Pregunta " + numero + ": ";
            if (p.enunciado() == null || p.enunciado().isBlank() || p.enunciado().length() > 500) {
                throw new ExamenInvalidoException(ubicacion + "escribe el enunciado (máximo 500 caracteres).");
            }
            if (p.opciones() == null || p.opciones().size() != PreguntaExamen.OPCIONES) {
                throw new ExamenInvalidoException(ubicacion + "debe tener exactamente " + PreguntaExamen.OPCIONES + " opciones.");
            }
            Set<String> distintas = new HashSet<>();
            for (String opcion : p.opciones()) {
                if (opcion == null || opcion.isBlank() || opcion.length() > 300) {
                    throw new ExamenInvalidoException(ubicacion + "ninguna opción puede estar vacía (máximo 300 caracteres).");
                }
                distintas.add(opcion.trim().toLowerCase());
            }
            if (distintas.size() != PreguntaExamen.OPCIONES) {
                throw new ExamenInvalidoException(ubicacion + "las 4 opciones deben ser distintas.");
            }
            if (p.respuestaCorrecta() < 0 || p.respuestaCorrecta() >= PreguntaExamen.OPCIONES) {
                throw new ExamenInvalidoException(ubicacion + "marca cuál de las 4 opciones es la correcta.");
            }
            numero++;
        }
    }

    // reemplaza el examen completo de un curso (se valida antes de guardar)
    @Transactional
    public void reemplazar(Long cursoId, List<PreguntaExamenInput> preguntas) {
        validar(preguntas);
        preguntaRepository.deleteByCursoId(cursoId);
        preguntaRepository.flush();
        List<PreguntaExamen> nuevas = new ArrayList<>();
        for (int i = 0; i < preguntas.size(); i++) {
            PreguntaExamenInput p = preguntas.get(i);
            nuevas.add(new PreguntaExamen(cursoId, i, p.enunciado().trim(),
                    p.opciones().stream().map(String::trim).toList(), p.respuestaCorrecta()));
        }
        preguntaRepository.saveAll(nuevas);
    }

    // califica un examen. respuestas[i] es la opcion elegida (0 a 3) para la pregunta i, -1 si la dejo en blanco.
    // Si aprueba, el curso queda completado (una sola vez, aunque mande el examen dos veces seguidas)
    @Transactional
    public ResultadoExamen presentar(Long cursoId, Long usuarioId, List<Integer> respuestas) {
        Curso curso = cursoRepository.findById(cursoId).orElseThrow(() -> new CursoNoEncontradoException(cursoId));
        if (!curso.isActivo()) {
            throw new CursoNoDisponibleException("Este curso ya no está disponible, no se puede presentar su examen.");
        }
        List<PreguntaExamen> preguntas = preguntasDe(cursoId);
        if (preguntas.isEmpty()) {
            throw new CursoNoDisponibleException("Este curso todavía no tiene examen, no se puede completar.");
        }
        if (respuestas == null || respuestas.size() != preguntas.size()) {
            throw new ExamenInvalidoException("Responde las " + preguntas.size() + " preguntas del examen.");
        }

        int aciertos = 0;
        for (int i = 0; i < preguntas.size(); i++) {
            Integer elegida = respuestas.get(i);
            if (elegida != null && elegida == preguntas.get(i).getRespuestaCorrecta()) {
                aciertos++;
            }
        }
        int porcentaje = Math.round(aciertos * 100f / preguntas.size());
        boolean aprobado = porcentaje >= MINIMO_APROBACION;

        boolean completado = completadoRepository.existsByUsuarioIdAndCursoId(usuarioId, cursoId);
        if (aprobado && !completado) {
            try {
                completadoRepository.saveAndFlush(new CursoCompletado(usuarioId, cursoId));
            } catch (DataIntegrityViolationException e) {
                // dos envios al mismo tiempo: el otro ya lo registro, no pasa nada
            }
            completado = true;
        }
        return new ResultadoExamen(aprobado, aciertos, preguntas.size(), porcentaje, MINIMO_APROBACION, completado);
    }
}
