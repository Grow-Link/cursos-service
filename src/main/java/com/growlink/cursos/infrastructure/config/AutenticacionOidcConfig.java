package com.growlink.cursos.infrastructure.config;

import com.growlink.cursos.infrastructure.security.ConvertidorDeTokenOidc;
import com.growlink.cursos.infrastructure.security.RegistroDeRechazosOidc;
import com.growlink.cursos.infrastructure.security.ValidadorDeAudiencia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Arrays;
import java.util.List;

// Piezas del modo auth.mode=oidc: validar el token que emite el proveedor de identidad (Microsoft Entra ID) como
// resource server, sin que este servicio guarde ni vea contrasenas. Nada de esto se crea en modo legacy, que es
// el valor por defecto.
//
// Todo sale de variables de entorno (ver application.yml):
//   AUTH_ISSUER_URI    quien firma los tokens que se aceptan, p.ej. https://login.microsoftonline.com/<tenant>/v2.0
//   AUTH_AUDIENCE      para que aplicacion tienen que venir, p.ej. api://growlink-cursos
//   AUTH_JWK_SET_URI   (opcional) donde estan las llaves publicas. Si no se pone, se descubren a partir del
//                      emisor (una peticion al arrancar, al documento de configuracion OpenID del emisor)
@Configuration
@ConditionalOnProperty(name = "auth.mode", havingValue = "oidc")
public class AutenticacionOidcConfig {

    private static final Logger log = LoggerFactory.getLogger(AutenticacionOidcConfig.class);

    // Lo que se le exige a cada token: que no este vencido (ni usado antes de tiempo), que lo firme el emisor
    // configurado y que venga dirigido a este servicio. La firma la comprueba el decodificador con las llaves
    // publicas del emisor. Es un bean propio para que se pueda probar sin conexion: la prueba arma el
    // decodificador con una llave generada en la propia prueba y le pone ESTAS mismas reglas.
    @Bean
    public OAuth2TokenValidator<Jwt> validadorDeTokenOidc(@Value("${auth.oidc.issuer-uri}") String emisor,
                                                          @Value("${auth.oidc.audience}") String audiencia) {
        if (emisor.isBlank() || audiencia.isBlank()) {
            throw new IllegalStateException("Con auth.mode=oidc hacen falta AUTH_ISSUER_URI y AUTH_AUDIENCE");
        }
        log.info("Autenticacion en modo OIDC: se aceptan tokens de {} dirigidos a {}", emisor, audiencia);
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(emisor),
                new ValidadorDeAudiencia(audiencia));
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(@Value("${auth.oidc.issuer-uri}") String emisor,
                                 @Value("${auth.oidc.jwk-set-uri:}") String jwkSetUri,
                                 OAuth2TokenValidator<Jwt> validador) {
        NimbusJwtDecoder decodificador = jwkSetUri.isBlank()
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(emisor)
                : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decodificador.setJwtValidator(validador);
        return decodificador;
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> convertidorDeTokenOidc(
            @Value("${auth.oidc.claims-usuario:growlink_uid,uid,sub}") String claimsUsuario,
            @Value("${auth.oidc.claims-roles:roles,rol,groups}") String claimsRoles) {
        return new ConvertidorDeTokenOidc(lista(claimsUsuario), lista(claimsRoles));
    }

    @Bean
    public RegistroDeRechazosOidc registroDeRechazosOidc() {
        return new RegistroDeRechazosOidc();
    }

    private static List<String> lista(String valores) {
        return Arrays.stream(valores.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
