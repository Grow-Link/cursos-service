package com.growlink.cursos.web;

import io.jsonwebtoken.Jwts;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

// Hace de proveedor de identidad para las pruebas del modo oidc: genera su propio par de llaves RSA y firma
// tokens con la privada, sin conectarse nunca a Microsoft. Lo unico que se le reemplaza al servicio es de donde
// saca las llaves PUBLICAS (en produccion las trae de Entra por HTTPS); las reglas que validan el token son las
// de verdad, las del bean validadorDeTokenOidc.
//
// LLAVE_AJENA es un segundo par con el que se firman los tokens "mal firmados": asi la prueba de firma invalida
// es de verdad una firma que no corresponde, no un token roto.
final class LlavesYTokensDePrueba {

    static final KeyPair PAR = generar();
    static final KeyPair PAR_AJENO = generar();

    private LlavesYTokensDePrueba() {
    }

    private static KeyPair generar() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo generar el par de llaves de la prueba", e);
        }
    }

    /** El decodificador que usan las pruebas: la llave publica de aqui y las reglas de validacion del servicio. */
    static JwtDecoder decodificadorCon(OAuth2TokenValidator<Jwt> validadorDeVerdad) {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withPublicKey((RSAPublicKey) PAR.getPublic()).build();
        decodificador.setJwtValidator(validadorDeVerdad);
        return decodificador;
    }

    /**
     * Un token con la forma del de Entra: `sub` opaco (como el de verdad), el inquilino en `tid` y el id numerico
     * de GrowLink en un claim de la aplicacion. Cada parametro se puede torcer para probar un rechazo.
     */
    static String token(Datos datos) {
        var constructor = Jwts.builder()
                .subject(datos.sujeto)
                .issuer(datos.emisor)
                .audience().add(datos.audiencias).and()
                .issuedAt(Date.from(datos.desde))
                .claim("tid", datos.inquilino)
                .claim("growlink_uid", datos.usuarioId)
                .claim("roles", List.of("USUARIO"));
        // sin expiration a proposito cuando hasta es null: asi se puede probar el token que no vence nunca
        if (datos.hasta != null) {
            constructor.expiration(Date.from(datos.hasta));
        }
        return constructor.signWith(datos.llave, Jwts.SIG.RS256).compact();
    }

    /** Los datos del token, con valores razonables que cada prueba tuerce solo donde le interesa. */
    static final class Datos {
        private String emisor;
        private String inquilino;
        private List<String> audiencias;
        private PrivateKey llave = PAR.getPrivate();
        private String sujeto = "AAAAAAAAAAAAAAAAAAAAAIk_bMBSKsTxQ4Y-eV3VvQ4";
        private long usuarioId = 7777L;
        private Instant desde = Instant.now();
        private Instant hasta = Instant.now().plusSeconds(3600);

        static Datos de(String inquilino, String audiencia) {
            Datos datos = new Datos();
            datos.inquilino = inquilino;
            datos.emisor = "https://login.microsoftonline.com/" + inquilino + "/v2.0";
            datos.audiencias = List.of(audiencia);
            return datos;
        }

        Datos conEmisor(String emisor) {
            this.emisor = emisor;
            return this;
        }

        Datos conInquilino(String inquilino) {
            this.inquilino = inquilino;
            return this;
        }

        Datos conAudiencias(String... audiencias) {
            this.audiencias = List.of(audiencias);
            return this;
        }

        Datos firmadoConOtraLlave() {
            this.llave = PAR_AJENO.getPrivate();
            return this;
        }

        Datos sinSujeto() {
            this.sujeto = null;
            return this;
        }

        Datos queNoVence() {
            this.hasta = null;
            return this;
        }

        Datos vencido() {
            this.desde = Instant.now().minusSeconds(7200);
            this.hasta = Instant.now().minusSeconds(3600);
            return this;
        }

        long usuarioId() {
            return usuarioId;
        }

        String firmar() {
            return token(this);
        }
    }
}
