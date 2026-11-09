package com.growlink.cursos.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

// auth.mode=oidc. Exige los dos claims sin los que un token no se puede usar, y que NADIE mas comprueba:
//
//   exp: JwtTimestampValidator solo compara la fecha SI el token la trae; un token sin `exp` le parece bien, o
//        sea que seria valido para siempre. Con la app en multi-inquilino eso pesa mas todavia, porque el
//        universo de emisores aceptados deja de ser uno solo.
//   sub: sin sujeto el token no identifica a nadie. Aunque ConvertidorDeTokenOidc necesita ademas el id numerico
//        de GrowLink, esto se comprueba aqui, con el resto de la validacion, y no cuando ya se dio por bueno.
//
// La firma, el emisor y la audiencia se comprueban por separado (ver AutenticacionOidcConfig).
public class ValidadorDeClaimsObligatorios implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(ValidadorDeClaimsObligatorios.class);

    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "Al token le faltan claims obligatorios",
            "https://datatracker.ietf.org/doc/html/rfc9068#section-4");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getExpiresAt() == null) {
            return rechazar("el token no vence nunca (no trae claim exp)");
        }
        String sujeto = token.getSubject();
        if (sujeto == null || sujeto.isBlank()) {
            return rechazar("el token no identifica a nadie (no trae claim sub)");
        }
        return OAuth2TokenValidatorResult.success();
    }

    // el motivo al log, al cliente solo el 401 generico
    private static OAuth2TokenValidatorResult rechazar(String motivo) {
        log.warn("Token rechazado: {}", motivo);
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
