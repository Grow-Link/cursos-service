package com.growlink.cursos.infrastructure.security;

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
}
