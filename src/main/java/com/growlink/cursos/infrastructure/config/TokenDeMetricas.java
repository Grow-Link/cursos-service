package com.growlink.cursos.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// ATRIBUTO: seguridad y observabilidad. /actuator/prometheus solo le contesta a quien trae el token del scrape en la
// cabecera X-Metricas-Token (el mismo METRICAS_TOKEN en los tres servicios y en la configuracion de Prometheus, ver
// ADR-0009 en el repo infra). Lo que expone no son credenciales, pero si el mapa del servicio: rutas, volumen de
// trafico, memoria, cuantos cursos se completan. Y el servicio es publico (por el nginx del front y, en Azure, por su
// propia URL), asi que no se puede dejar abierto.
//
// Va en una cabecera propia y no en Authorization a proposito: en modo oidc el resource server toma cualquier
// "Authorization: Bearer ..." como un token de Microsoft y lo rechazaria antes de llegar aqui.
//
// Sin el token la peticion se rechaza como cualquier otra sin credenciales: 403 en modo legacy y 401 en oidc (ver
// SecurityConfig). Sin METRICAS_TOKEN configurado el endpoint queda CERRADO para todos, no abierto: olvidar la
// variable no puede publicar las metricas. Un token de menos de 16 caracteres no deja arrancar, igual que un secreto
// corto del JWT.
@Component
public class TokenDeMetricas {

    public static final String CABECERA = "X-Metricas-Token";
    static final int LARGO_MINIMO = 16;

    private static final Logger log = LoggerFactory.getLogger(TokenDeMetricas.class);

    // null = no hay token configurado: nadie entra
    private final byte[] token;

    public TokenDeMetricas(@Value("${observabilidad.metricas-token:}") String token) {
        String limpio = token == null ? "" : token.trim();
        if (!limpio.isEmpty() && limpio.length() < LARGO_MINIMO) {
            throw new IllegalStateException("observabilidad.metricas-token (METRICAS_TOKEN) tiene que tener al menos "
                    + LARGO_MINIMO + " caracteres");
        }
        this.token = limpio.isEmpty() ? null : limpio.getBytes(StandardCharsets.UTF_8);
        if (this.token == null) {
            log.info("METRICAS_TOKEN vacio: /actuator/prometheus queda cerrado para todos");
        }
    }

    /** true si la peticion trae el token del scrape. Se compara en tiempo constante, para no ir adivinandolo. */
    public boolean valido(HttpServletRequest request) {
        if (token == null) {
            return false;
        }
        String recibido = request.getHeader(CABECERA);
        return recibido != null && MessageDigest.isEqual(token, recibido.trim().getBytes(StandardCharsets.UTF_8));
    }
}
