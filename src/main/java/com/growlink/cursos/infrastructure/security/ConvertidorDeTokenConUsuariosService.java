package com.growlink.cursos.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

// auth.mode=oidc con auth.identity-source=usuarios-service (lo normal). Corre DESPUES de que el token paso toda
// la validacion (firma con las llaves de Entra, emisor, audiencia, exp, sub...): a estas alturas ya se sabe que
// el token es autentico, lo que falta es saber a QUIEN de GrowLink corresponde.
//
// Eso se le pregunta a usuarios-service (ver ClienteDeUsuariosService) y con la respuesta se arma el MISMO
// Authentication que deja TokenAuthenticationFilter en modo legacy: principal = usuarioId (Long) y permisos
// ROLE_<rol>. Asi AuthenticatedUser, los controllers y las reglas de permisos no se enteran de con que modo se
// autentico la persona.
//
// La diferencia con ConvertidorDeTokenOidc (identity-source=claims) es de quien se cree el rol: aqui, de
// usuarios-service, que es su dueno; alla, del propio token, que es util en desarrollo pero no es algo que se
// quiera creer en produccion.
public class ConvertidorDeTokenConUsuariosService implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(ConvertidorDeTokenConUsuariosService.class);

    private final ClienteDeUsuariosService cliente;

    public ConvertidorDeTokenConUsuariosService(ClienteDeUsuariosService cliente) {
        this.cliente = cliente;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        IdentidadDeUsuario identidad = cliente.identidadDe(token);
        return new UsernamePasswordAuthenticationToken(identidad.usuarioId(), null, permisosDe(identidad));
    }

    // Un usuario sin rol todavia (en usuarios-service el rol se elige despues de entrar) queda autenticado pero
    // sin permisos: puede usar lo que no pide rol, y lo que si lo pide le responde 403. Es mejor que inventarle
    // uno aqui.
    private static List<GrantedAuthority> permisosDe(IdentidadDeUsuario identidad) {
        String rol = identidad.rol();
        if (rol == null || rol.isBlank()) {
            log.warn("usuarios-service no devolvio rol para el usuario {}: queda autenticado sin permisos",
                    identidad.usuarioId());
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + rol.trim()));
    }
}
