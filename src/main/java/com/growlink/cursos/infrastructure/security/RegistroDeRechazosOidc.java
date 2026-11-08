package com.growlink.cursos.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

// auth.mode=oidc. Es el unico sitio que decide que ve el cliente cuando no se le pudo autenticar, y deja el
// motivo en el log: un 401 sin rastro es imposible de diagnosticar (no se sabe si el token no llego, si vencio,
// si venia de otro emisor o si era para otra aplicacion).
//
// Distingue DOS cosas que no son lo mismo:
//   - el token no sirve                 -> 401, con la cabecera WWW-Authenticate: Bearer que escribe Spring. Al
//                                          cliente le toca conseguir otro token (o iniciar sesion).
//   - no se pudo resolver la identidad  -> 503 (IdentidadNoDisponibleException): usuarios-service no contesto. El
//                                          token puede estar perfecto y el problema ser nuestro, asi que mandar
//                                          un 401 haria que la gente intentara iniciar sesion otra vez para nada.
//                                          Con 503 + Retry-After, el cliente (y cualquier proxy) sabe reintentar.
//
// Lo que NUNCA se escribe es el token: aunque este vencido o mal firmado, un token en el log es una credencial en
// el log. El X-Request-Id sale solo, en cada linea, por el patron del log del proyecto (ver application.yml).
public class RegistroDeRechazosOidc implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeRechazosOidc.class);

    private final BearerTokenAuthenticationEntryPoint estandar = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (exception instanceof IdentidadNoDisponibleException) {
            noSePudoResolverLaIdentidad(request, response, exception);
            return;
        }
        boolean sinToken = request.getHeader("Authorization") == null;
        log.warn("Autenticacion rechazada en {} {}: {} ({})", request.getMethod(), request.getRequestURI(),
                sinToken ? "no llego ningun token" : "el token no paso la validacion",
                exception.getMessage());
        estandar.commence(request, response, exception);
    }

    private static void noSePudoResolverLaIdentidad(HttpServletRequest request, HttpServletResponse response,
                                                    AuthenticationException exception) throws IOException {
        // en ERROR, no en WARN: esto no es un cliente portandose mal, es una dependencia nuestra caida
        log.error("No se pudo resolver la identidad en usuarios-service para {} {}: se responde 503. Motivo: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", "5");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // misma forma que el resto de los errores del servicio (ver GlobalExceptionHandler.ErrorBody)
        response.getWriter().write("{\"message\":\"No se pudo verificar tu identidad en este momento. "
                + "Vuelve a intentarlo en unos segundos.\"}");
    }
}
