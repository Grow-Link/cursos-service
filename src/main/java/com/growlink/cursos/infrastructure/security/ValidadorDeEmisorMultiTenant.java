package com.growlink.cursos.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// auth.mode=oidc con auth.multi-tenant=true. La aplicacion esta registrada en Microsoft Entra ID como
// multi-inquilino (cuentas de cualquier directorio organizacional y cuentas personales de Microsoft), asi que el
// emisor del token NO es uno fijo: trae el inquilino de quien inicio sesion. Comparar el `iss` con un solo emisor
// rechazaria a casi todo el mundo, pero aceptar cualquier `iss` seria regalar el servicio: bastaria con que
// alguien levantara su propio emisor.
//
// El punto medio, que es lo que hace esta clase:
//   1. el `iss` tiene que encajar ENTERO en el patron de un inquilino de Entra: https, el host exacto
//      login.microsoftonline.com, un GUID y /v2.0. Asi se caen http://, otros hosts y los parecidos
//      (login.microsoftonline.com.evil.com, login.microsoftonline.com.co...), que es justo el ataque que
//      importa aqui;
//   2. el claim `tid` (el inquilino que dice el token) tiene que ser el MISMO GUID que aparece en el `iss`: sin
//      esto, un token de un inquilino podria presentarse como si fuera de otro;
//   3. si hay lista de inquilinos permitidos (AUTH_ALLOWED_TENANTS), el inquilino tiene que estar en ella. Vacia
//      significa "cualquier inquilino de Entra", que es lo que pide una app multi-inquilino de verdad.
//
// Las cuentas personales de Microsoft no necesitan nada aparte: vienen con el inquilino
// 9188040d-6c67-4c5b-b112-36a304b66dad, que encaja en el patron como cualquier otro.
//
// La firma NO se comprueba aqui: eso lo hace el decodificador con las llaves publicas de Entra (en
// multi-inquilino, las de /common, ver AutenticacionOidcConfig).
public class ValidadorDeEmisorMultiTenant implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(ValidadorDeEmisorMultiTenant.class);

    private static final String GUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    // anclado a proposito, y se usa con matches() (que exige que encaje la cadena completa): un emisor que solo
    // CONTENGA algo parecido a esto no vale
    static final Pattern EMISOR_DE_ENTRA = Pattern.compile(
            "^https://login\\.microsoftonline\\.com/(" + GUID + ")/v2\\.0$");

    private static final Pattern SOLO_GUID = Pattern.compile("^" + GUID + "$");

    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "El emisor del token no es un inquilino aceptado de Microsoft Entra ID",
            "https://datatracker.ietf.org/doc/html/rfc9068#section-4");

    // vacio = cualquier inquilino que encaje en el patron. Siempre en minusculas, para comparar sin sorpresas
    private final Set<String> inquilinosPermitidos;

    public ValidadorDeEmisorMultiTenant(Collection<String> inquilinosPermitidos) {
        Set<String> normalizados = new LinkedHashSet<>();
        for (String inquilino : inquilinosPermitidos) {
            String limpio = inquilino.trim();
            if (limpio.isEmpty()) {
                continue;
            }
            // fail-fast: un GUID mal escrito en AUTH_ALLOWED_TENANTS no se puede descubrir en produccion dejando
            // fuera a gente que si deberia entrar. Mejor no arrancar
            if (!SOLO_GUID.matcher(limpio).matches()) {
                throw new IllegalArgumentException("AUTH_ALLOWED_TENANTS solo acepta GUIDs de inquilino separados "
                        + "por comas, y llego: '" + limpio + "'");
            }
            normalizados.add(limpio.toLowerCase(Locale.ROOT));
        }
        this.inquilinosPermitidos = Set.copyOf(normalizados);
        if (this.inquilinosPermitidos.isEmpty()) {
            log.info("Autenticacion multi-inquilino: se acepta cualquier inquilino de Microsoft Entra ID");
        } else {
            log.info("Autenticacion multi-inquilino: solo los inquilinos {}", this.inquilinosPermitidos);
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // se lee tal como vino, como texto: no se normaliza ni se reconstruye nada antes de compararlo
        String emisor = token.getClaimAsString(JwtClaimNames.ISS);
        if (emisor == null || emisor.isBlank()) {
            return rechazar("el token no trae emisor (claim iss)");
        }
        Matcher encaje = EMISOR_DE_ENTRA.matcher(emisor);
        if (!encaje.matches()) {
            return rechazar("el emisor '" + emisor + "' no es un inquilino de Microsoft Entra ID");
        }
        String inquilinoDelEmisor = encaje.group(1).toLowerCase(Locale.ROOT);

        String tid = token.getClaimAsString("tid");
        if (tid == null || tid.isBlank()) {
            return rechazar("el token no dice de que inquilino es (claim tid)");
        }
        if (!tid.trim().equalsIgnoreCase(inquilinoDelEmisor)) {
            return rechazar("el inquilino del token (tid) no es el del emisor");
        }
        if (!inquilinosPermitidos.isEmpty() && !inquilinosPermitidos.contains(inquilinoDelEmisor)) {
            return rechazar("el inquilino " + inquilinoDelEmisor + " no esta en AUTH_ALLOWED_TENANTS");
        }
        return OAuth2TokenValidatorResult.success();
    }

    // El motivo va al log (lo necesita quien diagnostica) pero NO al cliente: la respuesta es siempre el mismo
    // 401 generico, para no contarle a quien prueba tokens que parte de la validacion le falló.
    private static OAuth2TokenValidatorResult rechazar(String motivo) {
        log.warn("Token rechazado por el emisor: {}", motivo);
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
