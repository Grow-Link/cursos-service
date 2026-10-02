package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.ActualizarPrerequisitosRequest;
import com.growlink.cursos.adapter.web.dto.CompletadoResponse;
import com.growlink.cursos.adapter.web.dto.CompletarCursoRequest;
import com.growlink.cursos.adapter.web.dto.CrearCursoRequest;
import com.growlink.cursos.adapter.web.dto.CursoResponse;
import com.growlink.cursos.adapter.web.dto.EditarCursoRequest;
import com.growlink.cursos.adapter.web.dto.EstadoRoadmapResponse;
import com.growlink.cursos.adapter.web.dto.SugerirPrerequisitosRequest;
import com.growlink.cursos.adapter.web.dto.SugerirPrerequisitosResponse;
import com.growlink.cursos.application.CursoService;
import com.growlink.cursos.application.RoadmapService;
import com.growlink.cursos.domain.Categoria;
import com.growlink.cursos.domain.Curso;
import com.growlink.cursos.domain.Nivel;
import com.growlink.cursos.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cursos")
@Tag(name = "Cursos")
public class CursoController {

    private static final String NOTA_DUENO_O_ADMIN =
            "Requiere ser el publicador dueño del curso, o tener rol ADMIN (cualquier curso).";

    private final CursoService cursoService;
    private final RoadmapService roadmapService;

    public CursoController(CursoService cursoService, RoadmapService roadmapService) {
        this.cursoService = cursoService;
        this.roadmapService = roadmapService;
    }

    @Operation(summary = "Crear curso", description = NOTA_DUENO_O_ADMIN
            + " El publicadorUsuarioId del body debe ser el del propio usuario (salvo ADMIN).")
    @PostMapping
    public ResponseEntity<CursoResponse> crear(@Valid @RequestBody CrearCursoRequest request) {
        Curso curso = cursoService.crear(request.titulo(), request.descripcion(), request.categoria(),
                request.nivel(), request.habilidadIds(), request.linkContenido(), request.publicadorUsuarioId(),
                request.prerequisitoIds(), AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return ResponseEntity.status(HttpStatus.CREATED).body(aRespuesta(curso));
    }

    @GetMapping("/{id}")
    public CursoResponse obtener(@PathVariable Long id) {
        return aRespuesta(cursoService.obtener(id));
    }

    // Sin publicadorUsuarioId: HU-13, catalogo general (solo activos, filtros opcionales)
    // Con publicadorUsuarioId: HU-07, "Mis cursos" del publicador (incluye inactivos)
    @GetMapping
    public List<CursoResponse> listar(@RequestParam(required = false) Long publicadorUsuarioId,
                                       @RequestParam(required = false) Categoria categoria,
                                       @RequestParam(required = false) Nivel nivel) {
        List<Curso> cursos = publicadorUsuarioId != null
                ? cursoService.listarPorPublicador(publicadorUsuarioId)
                : cursoService.catalogo(categoria, nivel);
        return cursos.stream().map(this::aRespuesta).toList();
    }

    // HU-10: dado un roadmap guardado, cuales de sus cursos ya no estan activos
    @GetMapping("/estado-roadmap")
    public EstadoRoadmapResponse estadoRoadmap(@RequestParam List<Long> ids) {
        return EstadoRoadmapResponse.from(cursoService.idsInactivosDe(ids));
    }

    // Para preseleccionar (editables) prerequisitos en el formulario de publicar
    // curso. Reutiliza la misma infraestructura de IA que el roadmap (ver RoadmapService).
    @PostMapping("/sugerir-prerequisitos")
    public SugerirPrerequisitosResponse sugerirPrerequisitos(@Valid @RequestBody SugerirPrerequisitosRequest request) {
        var resultado = roadmapService.sugerirPrerequisitos(request.categoria(), request.nivel(), request.titulo(),
                request.descripcion());
        return SugerirPrerequisitosResponse.from(resultado);
    }

    @Operation(summary = "Reemplazar prerequisitos de un curso", description = NOTA_DUENO_O_ADMIN)
    @PutMapping("/{id}/prerequisitos")
    public CursoResponse actualizarPrerequisitos(@PathVariable Long id,
                                                  @RequestBody ActualizarPrerequisitosRequest request) {
        cursoService.actualizarPrerequisitos(id, request.prerequisitoIds(),
                AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return aRespuesta(cursoService.obtener(id));
    }

    // HU-08: editar titulo, descripcion, nivel, habilidades, link
    @Operation(summary = "Editar curso", description = NOTA_DUENO_O_ADMIN)
    @PutMapping("/{id}")
    public CursoResponse editar(@PathVariable Long id, @Valid @RequestBody EditarCursoRequest request) {
        Curso curso = cursoService.editar(id, request.titulo(), request.descripcion(), request.nivel(),
                request.habilidadIds(), request.linkContenido(),
                AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return aRespuesta(curso);
    }

    // HU-09: baja logica, no borra la fila
    @Operation(summary = "Dar de baja un curso (baja lógica)", description = NOTA_DUENO_O_ADMIN)
    @PatchMapping("/{id}/baja")
    public ResponseEntity<Void> darDeBaja(@PathVariable Long id) {
        cursoService.darDeBaja(id, AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return ResponseEntity.noContent().build();
    }

    // HU-14: marcar un curso como completado
    @PostMapping("/{id}/completar")
    public ResponseEntity<Void> completar(@PathVariable Long id, @Valid @RequestBody CompletarCursoRequest request) {
        cursoService.completar(id, request.usuarioId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // HU-15: historial de completados de un usuario, incluye cursos ya inactivos
    @GetMapping("/completados")
    public List<CompletadoResponse> completados(@RequestParam Long usuarioId) {
        return cursoService.completados(usuarioId).stream().map(CompletadoResponse::from).toList();
    }

    private CursoResponse aRespuesta(Curso curso) {
        return CursoResponse.from(curso, cursoService.prerequisitosDe(curso.getId()));
    }
}
