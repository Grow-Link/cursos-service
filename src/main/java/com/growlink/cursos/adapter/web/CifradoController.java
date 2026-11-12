package com.growlink.cursos.adapter.web;

import com.growlink.cursos.infrastructure.config.CifradoDeCanal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

// La llave publica del servicio, para que el front pueda cifrar lo que le manda (ver CifradoFilter).
// Es publica a proposito: no hay nada secreto aqui, y hay que poder pedirla antes de tener sesion.
@RestController
@RequestMapping("/api/seguridad")
public class CifradoController {

    private final CifradoDeCanal canal;

    public CifradoController(CifradoDeCanal canal) {
        this.canal = canal;
    }

    @GetMapping("/clave-publica")
    public ResponseEntity<Map<String, String>> clavePublica() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(Map.of(
                        "algoritmo", "RSA-OAEP-256",
                        "cifradoDelCuerpo", "AES-256-GCM",
                        "clave", canal.clavePublica()));
    }
}
