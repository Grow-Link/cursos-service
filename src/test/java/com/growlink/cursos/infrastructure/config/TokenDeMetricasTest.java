package com.growlink.cursos.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// La regla del token del scrape, caso por caso (ver TokenDeMetricas). Con el servicio levantado lo prueba
// MetricasPrometheusTest.
class TokenDeMetricasTest {

    private static final String TOKEN = "un-token-de-scrape-largo";

    @Test
    void soloEntraConElTokenExacto() {
        TokenDeMetricas regla = new TokenDeMetricas(TOKEN);

        assertThat(regla.valido(conCabecera(TOKEN))).isTrue();
        // los espacios alrededor no cuentan (se cuelan al copiar el valor a una variable de entorno)
        assertThat(regla.valido(conCabecera("  " + TOKEN + " "))).isTrue();
        assertThat(regla.valido(conCabecera(TOKEN.toUpperCase()))).isFalse();
        assertThat(regla.valido(conCabecera(TOKEN + "x"))).isFalse();
        assertThat(regla.valido(conCabecera(""))).isFalse();
        assertThat(regla.valido(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void sinTokenConfiguradoNoEntraNadie() {
        // olvidar METRICAS_TOKEN deja las metricas cerradas, nunca abiertas
        for (String configurado : new String[]{"", "   ", null}) {
            TokenDeMetricas regla = new TokenDeMetricas(configurado);

            assertThat(regla.valido(conCabecera(""))).isFalse();
            assertThat(regla.valido(new MockHttpServletRequest())).isFalse();
        }
    }

    @Test
    void unTokenCortoNoDejaArrancar() {
        assertThatThrownBy(() -> new TokenDeMetricas("corto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("METRICAS_TOKEN");
    }

    private static MockHttpServletRequest conCabecera(String valor) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader(TokenDeMetricas.CABECERA, valor);
        return peticion;
    }
}
