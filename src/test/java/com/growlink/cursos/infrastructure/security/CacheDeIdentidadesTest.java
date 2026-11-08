package com.growlink.cursos.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

// Las reglas de la cache de identidades, con un reloj de mentiras: asi se puede comprobar al segundo cuando vence
// una entrada sin que la prueba tenga que esperar de verdad. Lo de extremo a extremo (que dos peticiones iguales
// cuesten una sola consulta, y que con la cache caliente se siga trabajando aunque usuarios-service se caiga)
// esta en IdentidadDesdeUsuariosServiceTest.
class CacheDeIdentidadesTest {

    private static final IdentidadDeUsuario ANA = new IdentidadDeUsuario(42L, "PUBLICADOR");

    // un reloj que se mueve solo cuando la prueba lo mueve
    private static final class RelojDeMentiras extends Clock {
        private Instant ahora = Instant.parse("2026-11-07T10:00:00Z");

        void avanzar(Duration cuanto) {
            ahora = ahora.plus(cuanto);
        }

        @Override
        public Instant instant() {
            return ahora;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }
    }

    @Test
    void guardaYDevuelveLaIdentidadMientrasEsteVigente() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofSeconds(60), 10, reloj);
        Instant expDelToken = reloj.instant().plus(Duration.ofHours(1));

        cache.guardar("huella-de-ana", ANA, expDelToken);

        assertThat(cache.buscar("huella-de-ana")).contains(ANA);
        reloj.avanzar(Duration.ofSeconds(59));
        assertThat(cache.buscar("huella-de-ana")).as("a los 59 s todavia vale").contains(ANA);
    }

    @Test
    void laIdentidadVenceAlCumplirseLaVigenciaConfigurada() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofSeconds(60), 10, reloj);
        cache.guardar("huella-de-ana", ANA, reloj.instant().plus(Duration.ofHours(1)));

        reloj.avanzar(Duration.ofSeconds(60));

        // a los 60 s hay que volver a preguntarle a usuarios-service: si a alguien le cambiaron el rol, este es
        // el retraso maximo con el que este servicio se entera
        assertThat(cache.buscar("huella-de-ana")).isEmpty();
    }

    @Test
    void unaIdentidadNuncaVivaMasQueElTokenQueLaTrajo() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        // la vigencia configurada es de 10 minutos, pero al token le quedan 30 segundos
        var cache = new CacheDeIdentidades(Duration.ofMinutes(10), 10, reloj);
        Instant expDelToken = reloj.instant().plus(Duration.ofSeconds(30));

        cache.guardar("huella-de-ana", ANA, expDelToken);

        reloj.avanzar(Duration.ofSeconds(29));
        assertThat(cache.buscar("huella-de-ana")).as("el token aun vale").contains(ANA);

        reloj.avanzar(Duration.ofSeconds(2));
        assertThat(cache.buscar("huella-de-ana"))
                .as("el token ya vencio: la cache no lo puede alargar")
                .isEmpty();
    }

    @Test
    void unTokenQueYaVencioNiSeGuarda() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofMinutes(10), 10, reloj);

        cache.guardar("huella-vieja", ANA, reloj.instant().minus(Duration.ofSeconds(1)));

        assertThat(cache.buscar("huella-vieja")).isEmpty();
        assertThat(cache.tamano()).isZero();
    }

    @Test
    void sinExpiracionDeTokenSeUsaSoloLaVigenciaConfigurada() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofSeconds(60), 10, reloj);

        cache.guardar("huella-de-ana", ANA, null);

        assertThat(cache.buscar("huella-de-ana")).contains(ANA);
        reloj.avanzar(Duration.ofSeconds(61));
        assertThat(cache.buscar("huella-de-ana")).isEmpty();
    }

    @Test
    void noPasaDelTopeDeEntradasAunqueLleguenTokensDistintosSinParar() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofMinutes(10), 50, reloj);
        Instant expDelToken = reloj.instant().plus(Duration.ofHours(1));

        // una lluvia de tokens distintos (lo que haria un script, o mucha gente a la vez) no puede hacer crecer
        // la memoria sin freno
        for (int i = 0; i < 500; i++) {
            cache.guardar("huella-" + i, new IdentidadDeUsuario((long) i, "USUARIO"), expDelToken);
        }

        assertThat(cache.tamano()).isLessThanOrEqualTo(50);
        // y la ultima que entro sigue estando: no se suelta lo recien traido
        assertThat(cache.buscar("huella-499")).isPresent();
    }

    @Test
    void alHacerSitioSeSueltaPrimeroLoQueYaVencio() {
        RelojDeMentiras reloj = new RelojDeMentiras();
        var cache = new CacheDeIdentidades(Duration.ofSeconds(60), 3, reloj);
        Instant expLejana = reloj.instant().plus(Duration.ofHours(1));
        cache.guardar("vieja-1", ANA, expLejana);
        cache.guardar("vieja-2", ANA, expLejana);

        reloj.avanzar(Duration.ofSeconds(61)); // las dos primeras ya vencieron
        cache.guardar("nueva", ANA, reloj.instant().plus(Duration.ofHours(1)));

        assertThat(cache.buscar("nueva")).isPresent();
        assertThat(cache.buscar("vieja-1")).isEmpty();
        assertThat(cache.buscar("vieja-2")).isEmpty();
    }

    @Test
    void laHuellaDelTokenNoEsElTokenYCambiaConCadaToken() {
        String uno = ClienteDeUsuariosService.huella("un.token.cualquiera");
        String otro = ClienteDeUsuariosService.huella("otro.token.cualquiera");

        // SHA-256 en hexadecimal: 64 caracteres, y en ningun caso el token en claro
        assertThat(uno).hasSize(64).doesNotContain("token");
        assertThat(uno).isNotEqualTo(otro);
        assertThat(ClienteDeUsuariosService.huella("un.token.cualquiera")).isEqualTo(uno);
    }
}
