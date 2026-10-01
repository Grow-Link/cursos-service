package com.growlink.cursos.adapter.web;

import com.growlink.cursos.application.CicloDePrerequisitosException;
import com.growlink.cursos.application.CursoNoEncontradoException;
import com.growlink.cursos.application.CursoYaCompletadoException;
import com.growlink.cursos.application.HabilidadCategoriaInvalidaException;
import com.growlink.cursos.application.HabilidadNoEncontradaException;
import com.growlink.cursos.application.NoAutorizadoException;
import com.growlink.cursos.application.RoadmapNoEncontradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    record ErrorBody(String message) {
    }

    @ExceptionHandler(CursoNoEncontradoException.class)
    public ResponseEntity<ErrorBody> handleNoEncontrado(CursoNoEncontradoException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(CicloDePrerequisitosException.class)
    public ResponseEntity<ErrorBody> handleCiclo(CicloDePrerequisitosException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(NoAutorizadoException.class)
    public ResponseEntity<ErrorBody> handleNoAutorizado(NoAutorizadoException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(HabilidadNoEncontradaException.class)
    public ResponseEntity<ErrorBody> handleHabilidadNoEncontrada(HabilidadNoEncontradaException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(HabilidadCategoriaInvalidaException.class)
    public ResponseEntity<ErrorBody> handleHabilidadCategoriaInvalida(HabilidadCategoriaInvalidaException e) {
        return ResponseEntity.badRequest().body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(CursoYaCompletadoException.class)
    public ResponseEntity<ErrorBody> handleCursoYaCompletado(CursoYaCompletadoException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(RoadmapNoEncontradoException.class)
    public ResponseEntity<ErrorBody> handleRoadmapNoEncontrado(RoadmapNoEncontradoException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidacion(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorBody(message));
    }
}
