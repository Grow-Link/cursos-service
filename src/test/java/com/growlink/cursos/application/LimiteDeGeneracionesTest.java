package com.growlink.cursos.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ATRIBUTO seguridad y costo: el tope de roadmaps por persona. El reloj es de mentira para probar el paso del tiempo
// (una ventana de 10 minutos no se puede esperar de verdad en una prueba).
class LimiteDeGeneracionesTest {

    static class RelojManual extends Clock {
        private Instant ahora = Instant.parse("2026-10-25T12:00:00Z");

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final RelojManual reloj = new RelojManual();

    @Test
    void permiteHastaElMaximoYRechazaElSiguienteDiciendoCuantoEsperar() {
        var limite = new LimiteDeGeneraciones(3, Duration.ofMinutes(10), reloj);
        for (int i = 0; i < 3; i++) {
            limite.registrar(1L);
            reloj.avanzar(Duration.ofMinutes(1));
        }
        // pasaron 3 minutos desde el primero: faltan 7 para que se libere un cupo
        assertThatThrownBy(() -> limite.registrar(1L))
                .isInstanceOf(LimiteExcedidoException.class)
                .satisfies(e -> {
                    long espera = ((LimiteExcedidoException) e).getSegundosParaReintentar();
                    assertThat(espera).isBetween(7 * 60L, 7 * 60L + 1);
                    assertThat(e.getMessage()).contains("8 minutos");
                });
    }

    @Test
    void conElPasoDelTiempoSeLiberanLosCupos() {
        var limite = new LimiteDeGeneraciones(2, Duration.ofMinutes(10), reloj);
        limite.registrar(1L);
        limite.registrar(1L);
        assertThatThrownBy(() -> limite.registrar(1L)).isInstanceOf(LimiteExcedidoException.class);

        reloj.avanzar(Duration.ofMinutes(10)); // la ventana se corrio: los dos intentos viejos ya no cuentan
        assertThatCode(() -> limite.registrar(1L)).doesNotThrowAnyException();
    }

    @Test
    void cadaPersonaTieneSuPropioCupo() {
        var limite = new LimiteDeGeneraciones(1, Duration.ofMinutes(10), reloj);
        limite.registrar(1L);
        assertThatThrownBy(() -> limite.registrar(1L)).isInstanceOf(LimiteExcedidoException.class);
        assertThatCode(() -> limite.registrar(2L)).doesNotThrowAnyException();
    }

    @Test
    void conMaximoCeroQuedaApagado() {
        var limite = new LimiteDeGeneraciones(0, Duration.ofMinutes(10), reloj);
        for (int i = 0; i < 50; i++) {
            limite.registrar(1L);
        }
    }

    @Test
    void elMensajeDiceLaEsperaEnUnidadesQueSeEntienden() {
        assertThat(new LimiteExcedidoException(1).getMessage()).contains("1 segundo");
        assertThat(new LimiteExcedidoException(45).getMessage()).contains("45 segundos");
        assertThat(new LimiteExcedidoException(60).getMessage()).contains("1 minuto");
        assertThat(new LimiteExcedidoException(61).getMessage()).contains("2 minutos");
    }
}
