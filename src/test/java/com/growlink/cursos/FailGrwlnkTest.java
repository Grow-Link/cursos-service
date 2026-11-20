package com.growlink.cursos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Prueba rota A PROPOSITO para la demo del escenario M1: el CI debe quedar en rojo y el despliegue no debe correr.
class FailGrwlnkTest {

    @Test
    void unaPruebaRotaDetieneElPipeline() {
        assertThat(1 + 1).as("FAIL-GRWLNK: prueba rota a proposito, escenario M1").isEqualTo(3);
    }
}
