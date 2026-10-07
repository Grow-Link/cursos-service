package com.growlink.cursos.infrastructure.security;

import com.growlink.cursos.application.NoAutorizadoException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// Helper para leer el usuario autenticado (userId + si es ADMIN) que puso
// TokenAuthenticationFilter en el SecurityContext, sin repetir ese codigo
// en cada controller.
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // el usuarioId que llega en el body o en la URL lo escribe el cliente, asi que no se le cree:
    // solo se puede actuar a nombre de uno mismo (un ADMIN si puede hacerlo por otros)
    public static void exigirPropio(Long usuarioIdPedido) {
        if (!isAdmin() && !currentUserId().equals(usuarioIdPedido)) {
            throw new NoAutorizadoException("Solo puedes hacer esto con tu propio usuario");
        }
    }
}
