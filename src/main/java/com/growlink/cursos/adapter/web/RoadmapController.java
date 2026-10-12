package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.RoadmapCompletadoResponse;
import com.growlink.cursos.adapter.web.dto.RoadmapGenerarRequest;
import com.growlink.cursos.adapter.web.dto.RoadmapResponse;
import com.growlink.cursos.application.RoadmapService;
import com.growlink.cursos.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roadmap")
@Tag(name = "Roadmap")
public class RoadmapController {

    private final RoadmapService roadmapService;

    public RoadmapController(RoadmapService roadmapService) {
        this.roadmapService = roadmapService;
    }

    // HU-11: cualquier usuario autenticado puede generar su propio roadmap
    @PostMapping("/generar")
    public ResponseEntity<RoadmapResponse> generar(@Valid @RequestBody RoadmapGenerarRequest request) {
        AuthenticatedUser.exigirPropio(request.usuarioId());
        var detalle = roadmapService.generar(request.usuarioId(), request.metas(), request.intereses(),
                request.nivel(), request.horasPorSemana(), request.semanas());
        return ResponseEntity.status(HttpStatus.CREATED).body(RoadmapResponse.from(detalle));
    }

    // Lo usa el Home (HU-05) para saber si el usuario ya tiene roadmap generado
    @GetMapping("/mio")
    public RoadmapResponse mio(@RequestParam Long usuarioId) {
        AuthenticatedUser.exigirPropio(usuarioId);
        return RoadmapResponse.from(roadmapService.mio(usuarioId));
    }

    // CONTRATO con usuarios-service: da una semilla de jardin por cada roadmap completado. No cambiar la forma
    // de esta respuesta sin avisar antes (ver RoadmapCompletadoResponse).
    @Operation(summary = "Roadmaps que esta persona ya completo del todo",
            description = "Solo la propia persona o un ADMIN. Lista vacia si no tiene ninguno completado.")
    @GetMapping("/completados")
    public List<RoadmapCompletadoResponse> completados(@RequestParam Long usuarioId) {
        AuthenticatedUser.exigirPropio(usuarioId);
        return roadmapService.completados(usuarioId).stream().map(RoadmapCompletadoResponse::from).toList();
    }
}
