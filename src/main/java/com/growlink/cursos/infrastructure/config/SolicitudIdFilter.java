package com.growlink.cursos.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// ATRIBUTO: observabilidad. Cada peticion lleva un identificador (X-Request-Id): si el balanceador o el servicio que
// llama ya mando uno valido, se respeta (asi la misma peticion se sigue de un servicio a otro); si no, se crea.
// Sale en la respuesta y en cada linea del log (%X{rid}), y se borra al terminar para que un hilo reutilizado no
// arrastre el de otra peticion. Solo se acepta un valor corto y sin caracteres raros: lo manda el cliente, no se le cree.
// Va justo despues de InstanciaHeaderFilter para que salga tambien en las respuestas rechazadas (401, 403).
// Al terminar cada peticion deja UNA linea de log (INFO) con el metodo, la ruta, el codigo de respuesta y lo que tardo: asi una
// peticion que salio bien tambien se puede rastrear por su identificador, de un servicio a otro. De la ruta solo se escribe el
// patron que atendio la peticion (/api/roadmap/mio, no lo que escribio quien llamo) y el metodo sale de una lista fija, asi que
// nada que mande el cliente llega al log. La salud y las metricas (/actuator) no se anotan: las consulta el sistema cada pocos segundos.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SolicitudIdFilter extends OncePerRequestFilter {

    public static final String CABECERA = "X-Request-Id";
    public static final String CLAVE_MDC = "rid";
    private static final Logger LOG = LoggerFactory.getLogger(SolicitudIdFilter.class);
    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String entrante = request.getHeader(CABECERA);
        String id = entrante != null && VALIDO.matcher(entrante).matches() ? entrante : UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, id);
        response.setHeader(CABECERA, id);
        long inicio = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            anotar(request, response, inicio);
            MDC.remove(CLAVE_MDC);
        }
    }

    private static void anotar(HttpServletRequest request, HttpServletResponse response, long inicio) {
        if (request.getRequestURI().startsWith("/actuator")) {
            return;
        }
        Object patron = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String ruta = patron instanceof String p ? p : "(sin ruta)";
        long milisegundos = (System.nanoTime() - inicio) / 1_000_000;
        LOG.info("peticion {} {} -> {} en {} ms", metodoConocido(request.getMethod()), ruta, response.getStatus(), milisegundos);
    }

    // solo los metodos HTTP de siempre, escritos aqui: cualquier otra cosa que mande un cliente se anota como OTRO
    private static String metodoConocido(String metodo) {
        return switch (metodo) {
            case "GET" -> "GET";
            case "POST" -> "POST";
            case "PUT" -> "PUT";
            case "PATCH" -> "PATCH";
            case "DELETE" -> "DELETE";
            case "HEAD" -> "HEAD";
            case "OPTIONS" -> "OPTIONS";
            default -> "OTRO";
        };
    }
}
