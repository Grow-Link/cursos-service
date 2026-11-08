package com.growlink.cursos.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

// auth.mode=oidc. Deja en el log cada token rechazado antes de responder 401. El 401 lo escribe el punto de
// entrada estandar de Spring (con su cabecera WWW-Authenticate: Bearer); aqui solo se anota el evento, porque un
// 401 sin rastro es imposible de diagnosticar: no se sabe si el token no llego, si vencio, si venia de otro
// emisor o si era para otra aplicacion.
//
// Lo que se escribe es el motivo que da la validacion, el metodo y la ruta. Nunca el token: aunque este vencido
// o mal firmado, un token en el log es una credencial en el log. El X-Request-Id sale solo, en cada linea, por el
// patron del log del proyecto (ver application.yml).
public class RegistroDeRechazosOidc implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeRechazosOidc.class);

    private final BearerTokenAuthenticationEntryPoint estandar = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        boolean sinToken = request.getHeader("Authorization") == null;
        log.warn("Autenticacion rechazada en {} {}: {} ({})", request.getMethod(), request.getRequestURI(),
                sinToken ? "no llego ningun token" : "el token no paso la validacion",
                exception.getMessage());
        estandar.commence(request, response, exception);
    }
}
