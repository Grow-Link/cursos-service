package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.ActualizarPrerequisitosRequest;
import com.growlink.cursos.adapter.web.dto.AreaResumenResponse;
import com.growlink.cursos.adapter.web.dto.CompletadoResponse;
import com.growlink.cursos.adapter.web.dto.CompletarCursoRequest;
import com.growlink.cursos.adapter.web.dto.CrearCursoRequest;
import com.growlink.cursos.adapter.web.dto.CursoResponse;
import com.growlink.cursos.adapter.web.dto.EditarCursoRequest;
import com.growlink.cursos.adapter.web.dto.EstadoRoadmapResponse;
import com.growlink.cursos.adapter.web.dto.ExamenResponse;
import com.growlink.cursos.adapter.web.dto.PreguntaExamenRequest;
import com.growlink.cursos.adapter.web.dto.PresentarExamenRequest;
import com.growlink.cursos.adapter.web.dto.ResultadoExamenResponse;
import com.growlink.cursos.adapter.web.dto.SugerirPrerequisitosRequest;
import com.growlink.cursos.adapter.web.dto.SugerirPrerequisitosResponse;
import com.growlink.cursos.application.CursoNoDisponibleException;
import com.growlink.cursos.application.CursoService;
import com.growlink.cursos.application.ExamenService;
import com.growlink.cursos.application.NoAutorizadoException;
import com.growlink.cursos.application.PreguntaExamenInput;
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
import java.util.Map;

@RestController
@RequestMapping("/api/cursos")
@Tag(name = "Cursos")
public class CursoController {

    private static final String NOTA_DUENO_O_ADMIN =
            "Requiere ser el publicador dueño del curso, o tener rol ADMIN (cualquier curso).";

    private final CursoService cursoService;
    private final RoadmapService roadmapService;
    private final ExamenService examenService;

    public CursoController(CursoService cursoService, RoadmapService roadmapService, ExamenService examenService) {
        this.cursoService = cursoService;
        this.roadmapService = roadmapService;
        this.examenService = examenService;
    }

    @Operation(summary = "Crear curso", description = NOTA_DUENO_O_ADMIN
            + " El publicadorUsuarioId del body debe ser el del propio usuario (salvo ADMIN)."
            + " Con examen (3 a 15 preguntas de 4 opciones) el curso se puede completar; sin examen no.")
    @PostMapping
    public ResponseEntity<CursoResponse> crear(@Valid @RequestBody CrearCursoRequest request) {
        Curso curso = cursoService.crear(request.titulo(), request.descripcion(), request.categoria(),
                request.nivel(), request.habilidadIds(), request.linkContenido(), request.publicadorUsuarioId(),
                request.prerequisitoIds(), request.duracionHoras(), request.temario(), aEntrada(request.examen()),
                AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
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
        Map<Long, Long> examenes = cursoService.totalPreguntasPorCurso(cursos.stream().map(Curso::getId).toList());
        return cursos.stream().map(c -> aRespuesta(c, examenes.getOrDefault(c.getId(), 0L))).toList();
    }

    @Operation(summary = "Areas con cursos",
            description = "Las areas que tienen cursos activos ahora mismo, con cuantos hay, sus niveles, las horas que "
                    + "suman, las habilidades principales y ejemplos. Es lo que se muestra antes de pedir la meta.")
    @GetMapping("/resumen")
    public List<AreaResumenResponse> resumen() {
        return cursoService.resumenAreas();
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

    // HU-08: editar titulo, descripcion, nivel, habilidades, link, horas, temario y (opcional) examen
    @Operation(summary = "Editar curso", description = NOTA_DUENO_O_ADMIN)
    @PutMapping("/{id}")
    public CursoResponse editar(@PathVariable Long id, @Valid @RequestBody EditarCursoRequest request) {
        Curso curso = cursoService.editar(id, request.titulo(), request.descripcion(), request.nivel(),
                request.habilidadIds(), request.linkContenido(), request.duracionHoras(), request.temario(),
                aEntrada(request.examen()), AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return aRespuesta(curso);
    }

    // HU-09: baja logica, no borra la fila
    @Operation(summary = "Dar de baja un curso (baja lógica)", description = NOTA_DUENO_O_ADMIN)
    @PatchMapping("/{id}/baja")
    public ResponseEntity<Void> darDeBaja(@PathVariable Long id) {
        cursoService.darDeBaja(id, AuthenticatedUser.currentUserId(), AuthenticatedUser.isAdmin());
        return ResponseEntity.noContent().build();
    }

    // El examen de un curso: las preguntas y opciones, nunca la respuesta correcta.
    @Operation(summary = "Ver el examen de un curso")
    @GetMapping("/{id}/examen")
    public ExamenResponse examen(@PathVariable Long id) {
        Curso curso = cursoService.obtener(id);
        if (!curso.isActivo()) {
            throw new CursoNoDisponibleException("Este curso ya no está disponible.");
        }
        var preguntas = examenService.preguntasDe(id);
        if (preguntas.isEmpty()) {
            throw new CursoNoDisponibleException("Este curso todavía no tiene examen.");
        }
        return new ExamenResponse(id, curso.getTitulo(), ExamenService.MINIMO_APROBACION,
                examenService.yaCompleto(AuthenticatedUser.currentUserId(), id),
                preguntas.stream().map(ExamenResponse.PreguntaPublica::from).toList());
    }

    // HU-14: la unica forma de completar un curso es aprobar su examen (minimo 70 %). El usuario es el del token.
    @Operation(summary = "Presentar el examen de un curso",
            description = "Si se aprueba (70 % o mas), el curso queda completado y se desbloquean sus habilidades.")
    @PostMapping("/{id}/examen")
    public ResultadoExamenResponse presentarExamen(@PathVariable Long id,
                                                    @Valid @RequestBody PresentarExamenRequest request) {
        return ResultadoExamenResponse.from(
                examenService.presentar(id, AuthenticatedUser.currentUserId(), request.respuestas()));
    }

    // Marcar un curso como completado sin examen: SOLO un ADMIN (para sembrar datos de demostracion o corregir
    // algo). Las personas completan cursos aprobando el examen.
    @Operation(summary = "Completar un curso sin examen (solo ADMIN)")
    @PostMapping("/{id}/completar")
    public ResponseEntity<Void> completar(@PathVariable Long id, @Valid @RequestBody CompletarCursoRequest request) {
        if (!AuthenticatedUser.isAdmin()) {
            throw new NoAutorizadoException("Un curso se completa aprobando su examen.");
        }
        cursoService.completar(id, request.usuarioId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // HU-15: historial de completados de un usuario, incluye cursos ya inactivos
    @GetMapping("/completados")
    public List<CompletadoResponse> completados(@RequestParam Long usuarioId) {
        AuthenticatedUser.exigirPropio(usuarioId);
        return cursoService.completados(usuarioId).stream().map(CompletadoResponse::from).toList();
    }

    private List<PreguntaExamenInput> aEntrada(List<PreguntaExamenRequest> examen) {
        return examen == null ? null : examen.stream().map(PreguntaExamenRequest::aEntrada).toList();
    }

    private CursoResponse aRespuesta(Curso curso) {
        return aRespuesta(curso, cursoService.totalPreguntasPorCurso(List.of(curso.getId()))
                .getOrDefault(curso.getId(), 0L));
    }

    private CursoResponse aRespuesta(Curso curso, long totalPreguntasExamen) {
        return CursoResponse.from(curso, cursoService.prerequisitosDe(curso.getId()), totalPreguntasExamen);
    }
}
