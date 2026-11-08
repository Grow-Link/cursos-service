package com.growlink.cursos.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// auth.mode=oidc. Traduce el token del proveedor de identidad a lo MISMO que deja TokenAuthenticationFilter en
// modo legacy: un Authentication cuyo principal es el usuarioId (Long) y cuyos permisos son ROLE_<rol>. Asi
// AuthenticatedUser y los controllers no se enteran de con que modo se autentico la persona y no hay que tocarlos.
//
// El id numerico de GrowLink se busca en los claims de `claimsUsuario`, en orden (el "sub" de Microsoft Entra ID
// es un texto opaco, no un numero, asi que normalmente el id propio viaja en un claim de la aplicacion). Un token
// bien firmado pero sin ningun claim numerico no se puede atender: no se sabe de quien es, y se responde 401.
public class ConvertidorDeTokenOidc implements Converter<Jwt, AbstractAuthenticationToken> {

    private final List<String> claimsUsuario;
    private final List<String> claimsRoles;

    public ConvertidorDeTokenOidc(List<String> claimsUsuario, List<String> claimsRoles) {
        this.claimsUsuario = List.copyOf(claimsUsuario);
        this.claimsRoles = List.copyOf(claimsRoles);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        Long usuarioId = usuarioIdDe(token);
        if (usuarioId == null) {
            throw new InvalidBearerTokenException("El token es valido pero no trae el id de usuario de GrowLink "
                    + "en ninguno de los claims " + claimsUsuario);
        }
        List<GrantedAuthority> permisos = rolesDe(token).stream()
                .map(rol -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + rol))
                .toList();
        return new UsernamePasswordAuthenticationToken(usuarioId, null, permisos);
    }

    private Long usuarioIdDe(Jwt token) {
        for (String claim : claimsUsuario) {
            Object valor = token.getClaim(claim);
            if (valor instanceof Number numero) {
                return numero.longValue();
            }
            if (valor instanceof String texto) {
                try {
                    return Long.valueOf(texto.trim());
                } catch (NumberFormatException e) {
                    // ese claim no es el id numerico (por ejemplo el sub de Entra ID): se prueba el siguiente
                }
            }
        }
        return null;
    }

    // igual que TokenService en modo legacy: el rol puede venir como lista ("roles") o como un solo texto ("rol")
    private Set<String> rolesDe(Jwt token) {
        Set<String> roles = new LinkedHashSet<>();
        for (String claim : claimsRoles) {
            Object valor = token.getClaim(claim);
            if (valor instanceof Collection<?> coleccion) {
                coleccion.forEach(rol -> roles.add(String.valueOf(rol)));
            } else if (valor instanceof String texto && !texto.isBlank()) {
                roles.add(texto.trim());
            }
        }
        return roles;
    }
}
