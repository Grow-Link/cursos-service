package com.growlink.cursos.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Set;

// auth.mode=oidc. Comprueba que el token venga dirigido A ESTE servicio (claim aud). Sin esto, cualquier token
// que el mismo proveedor de identidad haya emitido para OTRA aplicacion valdria aqui: la firma y el emisor
// estarian bien y solo faltaria esta comprobacion. Con la app en multi-inquilino esto pasa a ser la comprobacion
// que de verdad delimita "para quien es este token", porque el emisor ya no es uno solo.
//
// Se acepta una LISTA de audiencias (AUTH_AUDIENCE separada por comas) porque Entra pone en `aud` el
// `api://<client-id>` o el `<client-id>` pelado segun como este configurada la aplicacion y segun quien pida el
// token. Poner las dos formas evita que el servicio deje de funcionar por ese detalle; sigue siendo una lista
// cerrada y corta, no un comodin.
public class ValidadorDeAudiencia implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(ValidadorDeAudiencia.class);

    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "El token no fue emitido para este servicio (claim aud)",
            "https://datatracker.ietf.org/doc/html/rfc9068#section-4");

    private final Set<String> audienciasAceptadas;

    public ValidadorDeAudiencia(Collection<String> audienciasAceptadas) {
        this.audienciasAceptadas = Set.copyOf(audienciasAceptadas);
        if (this.audienciasAceptadas.isEmpty()) {
            throw new IllegalArgumentException("Hace falta al menos una audiencia (AUTH_AUDIENCE)");
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audienciasDelToken = token.getAudience();
        if (audienciasDelToken != null && audienciasDelToken.stream().anyMatch(audienciasAceptadas::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        log.warn("Token rechazado: su audiencia {} no es la de este servicio", audienciasDelToken);
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
