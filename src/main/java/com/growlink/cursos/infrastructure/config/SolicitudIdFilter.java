package com.growlink.cursos.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// ATRIBUTO: observabilidad. Cada peticion lleva un identificador (X-Request-Id): si el balanceador o el servicio que
// llama ya mando uno valido, se respeta (asi la misma peticion se sigue de un servicio a otro); si no, se crea.
// Sale en la respuesta y en cada linea del log (%X{rid}), y se borra al terminar para que un hilo reutilizado no
// arrastre el de otra peticion. Solo se acepta un valor corto y sin caracteres raros: lo manda el cliente, no se le cree.
// Va justo despues de InstanciaHeaderFilter para que salga tambien en las respuestas rechazadas (401, 403).
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SolicitudIdFilter extends OncePerRequestFilter {

    public static final String CABECERA = "X-Request-Id";
    public static final String CLAVE_MDC = "rid";
    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String entrante = request.getHeader(CABECERA);
        String id = entrante != null && VALIDO.matcher(entrante).matches() ? entrante : UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, id);
        response.setHeader(CABECERA, id);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
