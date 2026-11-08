package com.growlink.cursos.infrastructure.config;

import com.growlink.cursos.infrastructure.security.ConvertidorDeTokenOidc;
import com.growlink.cursos.infrastructure.security.RegistroDeRechazosOidc;
import com.growlink.cursos.infrastructure.security.ValidadorDeAudiencia;
import com.growlink.cursos.infrastructure.security.ValidadorDeClaimsObligatorios;
import com.growlink.cursos.infrastructure.security.ValidadorDeEmisorMultiTenant;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Piezas del modo auth.mode=oidc: validar el token que emite el proveedor de identidad (Microsoft Entra ID) como
// resource server, sin que este servicio guarde ni vea contrasenas. Nada de esto se crea en modo legacy, que es
// el valor por defecto.
//
// Todo sale de variables de entorno (ver application.yml):
//   AUTH_MULTI_TENANT     false (por defecto) = un solo emisor, el de AUTH_ISSUER_URI. true = la aplicacion esta
//                         registrada en Entra como multi-inquilino y el emisor trae el inquilino de quien inicia
//                         sesion, asi que se valida por patron + claim tid (ver ValidadorDeEmisorMultiTenant)
//   AUTH_ISSUER_URI       quien firma los tokens. Obligatoria solo con AUTH_MULTI_TENANT=false
//   AUTH_AUDIENCE         para que aplicacion tienen que venir. Acepta varias separadas por comas, porque Entra
//                         pone api://<client-id> o el <client-id> pelado segun la configuracion
//   AUTH_ALLOWED_TENANTS  (opcional, solo multi-inquilino) GUIDs de inquilino separados por comas. Vacia =
//                         cualquier inquilino de Entra
//   AUTH_JWKS_URI         (opcional) donde estan las llaves publicas. Con multi-inquilino, por defecto las de
//                         /common; con un solo inquilino, se descubren a partir del emisor
@Configuration
@ConditionalOnProperty(name = "auth.mode", havingValue = "oidc")
public class AutenticacionOidcConfig {

    private static final Logger log = LoggerFactory.getLogger(AutenticacionOidcConfig.class);

    // el juego de llaves con el que Entra firma para todos los inquilinos (el valor por defecto en
    // multi-inquilino; AUTH_JWKS_URI lo puede cambiar)
    static final String JWKS_COMUN_DE_ENTRA = "https://login.microsoftonline.com/common/discovery/v2.0/keys";

    // Lo que se le exige a cada token: vigencia, los claims sin los que no se puede usar, de quien viene y para
    // quien es. La firma la comprueba el decodificador con las llaves publicas del emisor. Es un bean propio para
    // que se pueda probar sin conexion: la prueba arma el decodificador con una llave generada en la propia
    // prueba y le pone ESTAS mismas reglas.
    //
    // Lo unico que cambia entre un modo y otro es COMO se comprueba el emisor; todo lo demas es identico, asi que
    // con AUTH_MULTI_TENANT=false el comportamiento es exactamente el de antes.
    @Bean
    public OAuth2TokenValidator<Jwt> validadorDeTokenOidc(@Value("${auth.oidc.issuer-uri:}") String emisor,
                                                          @Value("${auth.oidc.audience:}") String audiencia,
                                                          @Value("${auth.multi-tenant:false}") boolean multiInquilino,
                                                          @Value("${auth.oidc.allowed-tenants:}") String inquilinos) {
        List<String> audiencias = lista(audiencia);
        if (audiencias.isEmpty()) {
            throw new IllegalStateException("Con auth.mode=oidc hace falta AUTH_AUDIENCE");
        }

        List<OAuth2TokenValidator<Jwt>> reglas = new ArrayList<>();
        reglas.add(new JwtTimestampValidator());
        reglas.add(new ValidadorDeClaimsObligatorios());
        if (multiInquilino) {
            // el emisor ya no es uno fijo: se valida por patron y contra el claim tid
            reglas.add(new ValidadorDeEmisorMultiTenant(lista(inquilinos)));
            log.info("Autenticacion en modo OIDC multi-inquilino: se aceptan tokens de cualquier inquilino de "
                    + "Microsoft Entra ID permitido, dirigidos a {}", audiencias);
        } else {
            if (emisor.isBlank()) {
                throw new IllegalStateException("Con auth.mode=oidc y AUTH_MULTI_TENANT=false hace falta "
                        + "AUTH_ISSUER_URI (o AUTH_MULTI_TENANT=true si la app es multi-inquilino)");
            }
            reglas.add(new JwtIssuerValidator(emisor));
            log.info("Autenticacion en modo OIDC: se aceptan tokens de {} dirigidos a {}", emisor, audiencias);
        }
        reglas.add(new ValidadorDeAudiencia(audiencias));
        return new DelegatingOAuth2TokenValidator<>(reglas);
    }

    // Llaves publicas de Entra para comprobar la firma. En multi-inquilino NO se pueden descubrir a partir del
    // emisor (habria uno distinto por inquilino): se usan las de /common, que es el juego de llaves con el que
    // Entra firma para todos. En un solo inquilino se sigue descubriendo desde su emisor, como antes.
    // En los dos casos NimbusJwtDecoder acepta solo RS256.
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(@Value("${auth.oidc.issuer-uri:}") String emisor,
                                 @Value("${auth.oidc.jwk-set-uri:}") String jwkSetUri,
                                 @Value("${auth.multi-tenant:false}") boolean multiInquilino,
                                 OAuth2TokenValidator<Jwt> validador) {
        String llaves = !jwkSetUri.isBlank() ? jwkSetUri : (multiInquilino ? JWKS_COMUN_DE_ENTRA : "");
        NimbusJwtDecoder decodificador = llaves.isBlank()
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(emisor)
                : NimbusJwtDecoder.withJwkSetUri(llaves).build();
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
