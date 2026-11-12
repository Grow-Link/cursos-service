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
    private final MetricasDeCursos metricas;

    public ExamenService(PreguntaExamenRepository preguntaRepository, CursoCompletadoRepository completadoRepository,
                         CursoRepository cursoRepository, MetricasDeCursos metricas) {
        this.preguntaRepository = preguntaRepository;
        this.completadoRepository = completadoRepository;
        this.cursoRepository = cursoRepository;
        this.metricas = metricas;
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
    //
    // SIN @Transactional a proposito. Si dos envios del mismo examen aprobado llegan a la vez, los dos ven que el curso
    // todavia no estaba completado y los dos intentan guardarlo: la restriccion unica (usuarioId, cursoId) deja pasar a
    // uno y al otro le lanza el choque. Si todo esto corriera en UNA transaccion, el choque la dejaria marcada para
    // deshacerse aunque se atrape, y al confirmarla el que perdio recibiria un 500 (UnexpectedRollbackException). Sin
    // ella, cada lectura y el guardado van en su propia transaccion corta (la del repositorio): el choque solo deshace ese
    // guardado, y se atrapa como lo que es, que el otro envio ya lo registro. Tampoco hace falta mas: aqui solo se lee, se
    // califica y se guarda una fila, y lo unico que tiene que ser atomico (no guardar dos veces) lo cuida la restriccion.
    // Es una sola conexion a la vez, a diferencia de abrir una transaccion aparte (REQUIRES_NEW), que con muchos envios
    // simultaneos podria dejar el pool de conexiones sin ninguna libre. Ver ExamenDobleEnvioTest.
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
                // solo aqui, con el registro nuevo ya guardado y confirmado: un examen aprobado otra vez, o el envio que
                // perdio la carrera, no vuelven a contar
                metricas.cursoCompletado(MetricasDeCursos.VIA_EXAMEN, curso.getCategoria());
            } catch (DataIntegrityViolationException e) {
                // dos envios al mismo tiempo: el otro ya lo registro. Es la unica restriccion que puede saltar aqui (los
                // dos valores siempre vienen y la tabla no tiene llaves foraneas), y el resultado es el mismo para los dos
            }
            completado = true;
        }
        return new ResultadoExamen(aprobado, aciertos, preguntas.size(), porcentaje, MINIMO_APROBACION, completado);
    }
}
