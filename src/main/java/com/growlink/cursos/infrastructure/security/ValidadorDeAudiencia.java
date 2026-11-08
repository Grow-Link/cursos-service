package com.growlink.cursos.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

// auth.mode=oidc. Comprueba que el token venga dirigido A ESTE servicio (claim aud). Sin esto, cualquier token
// que el mismo proveedor de identidad haya emitido para OTRA aplicacion del mismo inquilino valdria aqui: la
// firma y el emisor estarian bien y solo faltaria esta comprobacion.
public class ValidadorDeAudiencia implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "El token no fue emitido para este servicio (claim aud)",
            "https://datatracker.ietf.org/doc/html/rfc9068#section-4");

    private final String audiencia;

    public ValidadorDeAudiencia(String audiencia) {
        this.audiencia = audiencia;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiencias = token.getAudience();
        if (audiencias != null && audiencias.contains(audiencia)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
