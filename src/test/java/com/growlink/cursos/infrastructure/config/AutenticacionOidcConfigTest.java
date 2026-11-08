package com.growlink.cursos.infrastructure.config;

import com.growlink.cursos.infrastructure.security.ConvertidorDeTokenConUsuariosService;
import com.growlink.cursos.infrastructure.security.ConvertidorDeTokenOidc;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ATRIBUTO seguridad y operabilidad: una configuracion que no tiene sentido tiene que tumbar el arranque, no
// descubrirse con la primera peticion de un usuario. Estas comprobaciones estan en los metodos @Bean, asi que si
// lanzan, Spring no levanta el contexto y el servicio no arranca: se prueban llamandolos directamente, que es
// mucho mas barato y preciso que levantar un contexto roto a proposito.
//
// El modo de autenticacion en si (auth.mode) tiene su propio fail-fast en SecurityConfig.
class AutenticacionOidcConfigTest {

    private final AutenticacionOidcConfig config = new AutenticacionOidcConfig();

    private static final String CLAIMS_USUARIO = "growlink_uid,uid,sub";
    private static final String CLAIMS_ROLES = "roles,rol,groups";

    // ------------------------------------------------------------------ de donde sale la identidad

    @Test
    void porDefectoLaIdentidadSeResuelveEnUsuariosService() {
        var convertidor = config.convertidorDeTokenOidc("usuarios-service", "http://usuarios:8081", 60, 10_000,
                CLAIMS_USUARIO, CLAIMS_ROLES);

        assertThat(convertidor).isInstanceOf(ConvertidorDeTokenConUsuariosService.class);
    }

    @Test
    void sinLaUrlDeUsuariosServiceElServicioNoArranca() {
        // descubrir esto con la primera peticion de un usuario seria enterarse tarde y en el peor momento
        assertThatThrownBy(() -> config.convertidorDeTokenOidc("usuarios-service", "  ", 60, 10_000,
                CLAIMS_USUARIO, CLAIMS_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USUARIOS_SERVICE_URL");
    }

    @Test
    void conIdentitySourceClaimsSeConservaElComportamientoAnterior() {
        var convertidor = config.convertidorDeTokenOidc("claims", "", 60, 10_000, CLAIMS_USUARIO, CLAIMS_ROLES);

        assertThat(convertidor).isInstanceOf(ConvertidorDeTokenOidc.class);
    }

    @Test
    void unIdentitySourceInventadoNoDejaArrancar() {
        // si se dejara pasar con un valor por defecto, un dedazo en la variable cambiaria en silencio de donde
        // sale el rol de la gente, que es lo ultimo que se quiere que pase sin avisar
        assertThatThrownBy(() -> config.convertidorDeTokenOidc("usuarios_service", "http://usuarios:8081", 60,
                10_000, CLAIMS_USUARIO, CLAIMS_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.identity-source");

        assertThatThrownBy(() -> config.convertidorDeTokenOidc("", "http://usuarios:8081", 60, 10_000,
                CLAIMS_USUARIO, CLAIMS_ROLES))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elValorDeIdentitySourceNoDependeDeMayusculas() {
        assertThat(config.convertidorDeTokenOidc("USUARIOS-SERVICE", "http://usuarios:8081", 60, 10_000,
                CLAIMS_USUARIO, CLAIMS_ROLES)).isInstanceOf(ConvertidorDeTokenConUsuariosService.class);
        assertThat(config.convertidorDeTokenOidc("Claims", "", 60, 10_000, CLAIMS_USUARIO, CLAIMS_ROLES))
                .isInstanceOf(ConvertidorDeTokenOidc.class);
    }

    // ------------------------------------------------------------------ emisor y audiencia

    @Test
    void sinAudienciaElServicioNoArranca() {
        assertThatThrownBy(() -> config.validadorDeTokenOidc("https://emisor/v2.0", "  ", false, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_AUDIENCE");
    }

    @Test
    void enUnSoloInquilinoSinEmisorElServicioNoArranca() {
        assertThatThrownBy(() -> config.validadorDeTokenOidc("", "api://growlink-cursos", false, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_ISSUER_URI");
    }

    @Test
    void enMultiInquilinoElEmisorFijoYaNoHaceFalta() {
        // es justo el punto de multi-inquilino: no hay UN emisor que configurar
        assertThat(config.validadorDeTokenOidc("", "api://growlink-cursos", true, "")).isNotNull();
    }
}
