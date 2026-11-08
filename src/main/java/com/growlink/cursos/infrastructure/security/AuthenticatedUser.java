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
        return tieneRol("ADMIN");
    }

    public static boolean tieneRol(String rol) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }

    // Publicar un curso es cosa de un PUBLICADOR (o de un ADMIN): el catalogo es lo que ve toda la plataforma,
    // asi que no puede escribir en el cualquiera que tenga cuenta.
    //
    // El rol sale de los permisos del SecurityContext, asi que esto funciona igual en los dos modos de
    // autenticacion sin saber en cual esta: en legacy lo pone TokenAuthenticationFilter leyendo el JWT
    // compartido, y en oidc lo pone el convertidor con lo que contesto usuarios-service (ver SecurityConfig).
    public static void exigirPuedePublicar() {
        if (!isAdmin() && !tieneRol("PUBLICADOR")) {
            throw new NoAutorizadoException("Solo un PUBLICADOR o un ADMIN puede crear cursos");
        }
    }

    // el usuarioId que llega en el body o en la URL lo escribe el cliente, asi que no se le cree:
    // solo se puede actuar a nombre de uno mismo (un ADMIN si puede hacerlo por otros)
    public static void exigirPropio(Long usuarioIdPedido) {
        if (!isAdmin() && !currentUserId().equals(usuarioIdPedido)) {
            throw new NoAutorizadoException("Solo puedes hacer esto con tu propio usuario");
        }
    }
}
