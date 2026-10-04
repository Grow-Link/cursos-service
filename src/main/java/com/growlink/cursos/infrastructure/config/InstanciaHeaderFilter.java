package com.growlink.cursos.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

// pone en cada respuesta el nombre de la instancia que la atendio (en Docker es el id del contenedor)
// sirve para ver el balanceo: si hay varias replicas, el header va cambiando entre peticiones
// va primero en la cadena para que salga tambien en las respuestas rechazadas (401, 403)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanciaHeaderFilter extends OncePerRequestFilter {

    private final String instancia = nombreDeInstancia();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Instancia", instancia);
        filterChain.doFilter(request, response);
    }

    private static String nombreDeInstancia() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "desconocida";
        }
    }
}
