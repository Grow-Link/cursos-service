package com.growlink.cursos.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ATRIBUTO seguridad: la regla del emisor en multi-inquilino, caso por caso. Va aparte de la prueba de extremo a
// extremo (TokenOidcMultiTenantTest) porque aqui se pueden recorrer barato los emisores raros que hay que
// rechazar, que es donde esta el riesgo de verdad: si el patron se afloja (por no estar anclado, o por comparar
// con "contiene" en vez de "es"), cualquiera que registre un dominio parecido entra.
class ValidadorDeEmisorMultiTenantTest {

    private static final String INQUILINO_A = "11111111-2222-3333-4444-555555555555";
    private static final String INQUILINO_B = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    // el inquilino de las cuentas personales de Microsoft, que no necesita ningun caso especial
    private static final String CUENTAS_PERSONALES = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private static Jwt token(String emisor, String tid) {
        Jwt.Builder constructor = Jwt.withTokenValue("no-importa")
                .header("alg", "RS256")
                .subject("sujeto-opaco")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .audience(List.of("api://growlink-cursos"));
        if (emisor != null) {
            constructor.claim("iss", emisor);
        }
        if (tid != null) {
            constructor.claim("tid", tid);
        }
        return constructor.build();
    }

    private static boolean acepta(ValidadorDeEmisorMultiTenant validador, String emisor, String tid) {
        return !validador.validate(token(emisor, tid)).hasErrors();
    }

    private static String emisorDe(String inquilino) {
        return "https://login.microsoftonline.com/" + inquilino + "/v2.0";
    }

    // ------------------------------------------------------------------ sin lista de permitidos

    @Test
    void sinListaDePermitidosEntraCualquierInquilinoDeEntra() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, emisorDe(INQUILINO_A), INQUILINO_A)).isTrue();
        assertThat(acepta(validador, emisorDe(INQUILINO_B), INQUILINO_B)).isTrue();
        assertThat(acepta(validador, emisorDe(CUENTAS_PERSONALES), CUENTAS_PERSONALES))
                .as("las cuentas personales de Microsoft tambien").isTrue();
    }

    @Test
    void elGuidPuedeVenirEnMayusculasYSeSigueComparandoBien() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());
        String enMayusculas = INQUILINO_A.toUpperCase();

        assertThat(acepta(validador, emisorDe(enMayusculas), enMayusculas)).isTrue();
        // y mezclado entre el iss y el tid: es el mismo inquilino
        assertThat(acepta(validador, emisorDe(enMayusculas), INQUILINO_A)).isTrue();
    }

    // ------------------------------------------------------------------ el tid tiene que ser el del emisor

    @Test
    void unTidDistintoAlDelEmisorSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        // emisor del inquilino A pero el token dice ser del B: uno de los dos miente
        assertThat(acepta(validador, emisorDe(INQUILINO_A), INQUILINO_B)).isFalse();
    }

    @Test
    void sinClaimTidSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, emisorDe(INQUILINO_A), null)).isFalse();
        assertThat(acepta(validador, emisorDe(INQUILINO_A), "   ")).isFalse();
    }

    // ------------------------------------------------------------------ emisores que NO son de Entra

    @Test
    void unEmisorConHostParecidoSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        List<String> disfraces = List.of(
                // el ataque clasico: un dominio que registra cualquiera y que "empieza igual"
                "https://login.microsoftonline.com.evil.com/" + INQUILINO_A + "/v2.0",
                "https://login.microsoftonline.com.co/" + INQUILINO_A + "/v2.0",
                "https://evil.com/login.microsoftonline.com/" + INQUILINO_A + "/v2.0",
                "https://login-microsoftonline.com/" + INQUILINO_A + "/v2.0",
                "https://login.microsoftonline.evil.com/" + INQUILINO_A + "/v2.0",
                // el host bueno, pero de subdominio de otro
                "https://login.microsoftonline.com@evil.com/" + INQUILINO_A + "/v2.0",
                // el emisor correcto, pero metido dentro de otra cosa (asi se cae un patron sin anclar)
                "https://evil.com/?x=" + emisorDe(INQUILINO_A));

        for (String emisor : disfraces) {
            assertThat(acepta(validador, emisor, INQUILINO_A)).as(emisor).isFalse();
        }
    }

    @Test
    void unEmisorPorHttpSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, "http://login.microsoftonline.com/" + INQUILINO_A + "/v2.0", INQUILINO_A))
                .isFalse();
    }

    @Test
    void unEmisorSinLaVersionV20SeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, "https://login.microsoftonline.com/" + INQUILINO_A, INQUILINO_A))
                .as("sin /v2.0").isFalse();
        assertThat(acepta(validador, "https://login.microsoftonline.com/" + INQUILINO_A + "/", INQUILINO_A))
                .as("solo con la barra").isFalse();
        assertThat(acepta(validador, "https://login.microsoftonline.com/" + INQUILINO_A + "/v1.0", INQUILINO_A))
                .as("otra version").isFalse();
        assertThat(acepta(validador, "https://login.microsoftonline.com/" + INQUILINO_A + "/v2.0/extra", INQUILINO_A))
                .as("algo despues de la version").isFalse();
    }

    @Test
    void unEmisorConAlgoQueNoEsUnGuidSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, "https://login.microsoftonline.com/common/v2.0", "common")).isFalse();
        assertThat(acepta(validador, "https://login.microsoftonline.com/organizations/v2.0", "organizations")).isFalse();
        assertThat(acepta(validador, "https://login.microsoftonline.com/no-es-un-guid/v2.0", "no-es-un-guid")).isFalse();
        // un GUID al que le falta o le sobra un caracter
        assertThat(acepta(validador, "https://login.microsoftonline.com/11111111-2222-3333-4444-55555555555/v2.0",
                "11111111-2222-3333-4444-55555555555")).isFalse();
    }

    @Test
    void sinEmisorSeRechaza() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of());

        assertThat(acepta(validador, null, INQUILINO_A)).isFalse();
    }

    // ------------------------------------------------------------------ con lista de permitidos

    @Test
    void conListaDePermitidosSoloEntranLosDeLaLista() {
        var validador = new ValidadorDeEmisorMultiTenant(List.of(INQUILINO_A));

        assertThat(acepta(validador, emisorDe(INQUILINO_A), INQUILINO_A)).as("esta en la lista").isTrue();
        assertThat(acepta(validador, emisorDe(INQUILINO_B), INQUILINO_B)).as("no esta en la lista").isFalse();
    }

    @Test
    void laListaDePermitidosAdmiteVariosYNoLeImportanLasMayusculasNiLosEspacios() {
        var validador = new ValidadorDeEmisorMultiTenant(
                List.of(INQUILINO_A.toUpperCase(), "  " + INQUILINO_B + "  "));

        assertThat(acepta(validador, emisorDe(INQUILINO_A), INQUILINO_A)).isTrue();
        assertThat(acepta(validador, emisorDe(INQUILINO_B), INQUILINO_B)).isTrue();
        assertThat(acepta(validador, emisorDe(CUENTAS_PERSONALES), CUENTAS_PERSONALES)).isFalse();
    }

    @Test
    void unGuidMalEscritoEnLaListaNoDejaArrancarElServicio() {
        // si se dejara pasar, en produccion se quedaria gente fuera sin que nada lo dijera
        assertThatThrownBy(() -> new ValidadorDeEmisorMultiTenant(List.of("esto-no-es-un-guid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTH_ALLOWED_TENANTS");
    }

    // ------------------------------------------------------------------ el patron, directo

    @Test
    void elPatronDelEmisorSoloEncajaConLaCadenaCompleta() {
        // matches() exige que encaje TODO: ni un salto de linea al final cuela
        assertThat(ValidadorDeEmisorMultiTenant.EMISOR_DE_ENTRA.matcher(emisorDe(INQUILINO_A)).matches()).isTrue();
        assertThat(ValidadorDeEmisorMultiTenant.EMISOR_DE_ENTRA.matcher(emisorDe(INQUILINO_A) + "\n").matches())
                .as("con un salto de linea detras").isFalse();
        assertThat(ValidadorDeEmisorMultiTenant.EMISOR_DE_ENTRA.matcher(" " + emisorDe(INQUILINO_A)).matches())
                .as("con un espacio delante").isFalse();
    }

    @Test
    void elValidadorNoMiraNadaMasQueElEmisorYElInquilino() {
        // un token sin audiencia ni nada mas pasa ESTA regla: de la audiencia se encarga ValidadorDeAudiencia y
        // de la vigencia los otros dos. Cada regla comprueba una cosa
        var validador = new ValidadorDeEmisorMultiTenant(List.of());
        Jwt pelado = Jwt.withTokenValue("no-importa").header("alg", "RS256")
                .claim("iss", emisorDe(INQUILINO_A))
                .claim("tid", INQUILINO_A)
                .claims(c -> c.putAll(Map.of()))
                .build();

        assertThat(validador.validate(pelado).hasErrors()).isFalse();
    }
}
