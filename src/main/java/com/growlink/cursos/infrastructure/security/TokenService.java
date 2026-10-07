package com.growlink.cursos.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// cursos-service no reimplementa autenticacion: solo valida la firma del JWT
// emitido por auth-service con el mismo secreto compartido (GROWLINK_JWT_SECRET)
// y extrae sub (userId) y roles. Mismo patron que usuarios-service, pero con
// userId Long: ese es el tipo que ya usa publicadorUsuarioId/usuarioId en este servicio.
@Component
public class TokenService {

    private final SecretKey secretKey;

    public TokenService(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public ClaimsJwt parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());

        // usuarios-service firma el rol en un solo claim de texto ("rol": "ADMIN"),
        // pero este servicio nacio esperando una lista ("roles": [...]). Se aceptan las dos,
        // si no, un ADMIN de verdad nunca contaba como admin aqui
        Set<String> roles = new LinkedHashSet<>();
        Object lista = claims.get("roles");
        if (lista instanceof Collection<?> coleccion) {
            coleccion.forEach(rol -> roles.add(String.valueOf(rol)));
        }
        String rol = claims.get("rol", String.class);
        if (rol != null) {
            roles.add(rol);
        }

        return new ClaimsJwt(userId, List.copyOf(roles));
    }

    public record ClaimsJwt(Long userId, List<String> roles) {
    }
}
