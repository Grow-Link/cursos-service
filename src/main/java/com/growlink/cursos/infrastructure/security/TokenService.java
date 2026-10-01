package com.growlink.cursos.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);

        return new ClaimsJwt(userId, roles == null ? List.of() : roles);
    }

    public record ClaimsJwt(Long userId, List<String> roles) {
    }
}
