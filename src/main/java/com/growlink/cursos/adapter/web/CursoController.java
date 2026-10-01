package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.ActualizarPrerequisitosRequest;
import com.growlink.cursos.adapter.web.dto.CrearCursoRequest;
import com.growlink.cursos.adapter.web.dto.CursoResponse;
import com.growlink.cursos.application.CursoService;
import com.growlink.cursos.domain.Curso;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cursos")
public class CursoController {

    private final CursoService cursoService;

    public CursoController(CursoService cursoService) {
        this.cursoService = cursoService;
    }

    @PostMapping
    public ResponseEntity<CursoResponse> crear(@Valid @RequestBody CrearCursoRequest request) {
        Curso curso = cursoService.crear(request.titulo(), request.descripcion(), request.categoria(),
                request.nivel(), request.habilidades(), request.linkContenido(), request.publicadorUsuarioId(),
                request.prerequisitoIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(aRespuesta(curso));
    }

    @GetMapping("/{id}")
    public CursoResponse obtener(@PathVariable Long id) {
        return aRespuesta(cursoService.obtener(id));
    }

    // HU-07: "Mis cursos" del publicador
    @GetMapping
    public List<CursoResponse> listarPorPublicador(@RequestParam Long publicadorUsuarioId) {
        return cursoService.listarPorPublicador(publicadorUsuarioId).stream().map(this::aRespuesta).toList();
    }

    // preview de HU-08: solo la parte de prerequisitos, ver nota en CursoService
    @PutMapping("/{id}/prerequisitos")
    public CursoResponse actualizarPrerequisitos(@PathVariable Long id,
                                                  @RequestBody ActualizarPrerequisitosRequest request) {
        cursoService.actualizarPrerequisitos(id, request.prerequisitoIds());
        return aRespuesta(cursoService.obtener(id));
    }

    private CursoResponse aRespuesta(Curso curso) {
        return CursoResponse.from(curso, cursoService.prerequisitosDe(curso.getId()));
    }
}
