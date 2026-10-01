package com.growlink.cursos.adapter.web;

import com.growlink.cursos.adapter.web.dto.HabilidadResponse;
import com.growlink.cursos.application.HabilidadService;
import com.growlink.cursos.domain.Categoria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/habilidades")
public class HabilidadController {

    private final HabilidadService habilidadService;

    public HabilidadController(HabilidadService habilidadService) {
        this.habilidadService = habilidadService;
    }

    @GetMapping
    public List<HabilidadResponse> listar(@RequestParam(required = false) Categoria categoria) {
        return habilidadService.listar(categoria).stream().map(HabilidadResponse::from).toList();
    }
}
