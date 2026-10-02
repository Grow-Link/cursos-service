package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.RoadmapGenerarRequest;
import com.growlink.cursos.adapter.web.dto.RoadmapResponse;
import com.growlink.cursos.application.RoadmapService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        var detalle = roadmapService.generar(request.usuarioId(), request.metas(), request.intereses(),
                request.nivel());
        return ResponseEntity.status(HttpStatus.CREATED).body(RoadmapResponse.from(detalle));
    }

    // Lo usa el Home (HU-05) para saber si el usuario ya tiene roadmap generado
    @GetMapping("/mio")
    public RoadmapResponse mio(@RequestParam Long usuarioId) {
        return RoadmapResponse.from(roadmapService.mio(usuarioId));
    }
}
