package com.growlink.cursos.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// ATRIBUTO disponibilidad (tolerancia a fallos): las reglas del cortacircuito de la IA, una por una y sin esperar
// los 60 segundos de verdad (los tiempos se inyectan). Lo que se comprueba es exactamente lo que se le promete a
// quien lo usa: cuando deja de dar permiso para llamar a la IA y cuando lo vuelve a dar.
class CortacircuitoDeLaIaTest {

    // tiempos de prueba: tres fallos para abrir (igual que en produccion), 60 ms de espera en vez de 60 s
    private static CortacircuitoDeLaIa cortacircuito() {
        return new CortacircuitoDeLaIa(3, Duration.ofMillis(60), Duration.ofSeconds(10));
    }

    private static void fallar(CortacircuitoDeLaIa cortacircuito) {
        CortacircuitoDeLaIa.Llamada llamada = cortacircuito.iniciar();
        assertThat(llamada).as("tendria que haber dado permiso").isNotNull();
        llamada.fallo(new RoadmapAiException("simulacro: Claude no contesto"));
    }

    private static void responder(CortacircuitoDeLaIa cortacircuito) {
        CortacircuitoDeLaIa.Llamada llamada = cortacircuito.iniciar();
        assertThat(llamada).as("tendria que haber dado permiso").isNotNull();
        llamada.exito();
    }

    @Test
    void seAbreALosTresFallosSeguidosYNoAntes() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");

        fallar(cortacircuito);
        assertThat(cortacircuito.estado()).as("con un fallo se sigue intentando").isEqualTo("CERRADO");
        fallar(cortacircuito);
        assertThat(cortacircuito.estado()).as("con dos fallos tambien").isEqualTo("CERRADO");
        fallar(cortacircuito);

        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");
        assertThat(cortacircuito.estadoComoNumero()).isEqualTo(2);
    }

    @Test
    void mientrasEstaAbiertoNoDaPermisoParaLlamarALaIa() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        fallar(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);

        // ni una: todas estas peticiones las tiene que atender el respaldo, sin tocar la IA
        for (int i = 0; i < 20; i++) {
            assertThat(cortacircuito.iniciar()).as("intento " + i).isNull();
        }
        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");
    }

    @Test
    void unaRespuestaQueNoSirveCuentaComoFalloIgualQueUnError() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();

        // la IA contesto las tres veces, pero las tres veces con una ruta que no es del catalogo real
        for (int i = 0; i < 3; i++) {
            CortacircuitoDeLaIa.Llamada llamada = cortacircuito.iniciar();
            assertThat(llamada).isNotNull();
            llamada.respuestaInvalida();
        }

        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");
    }

    @Test
    void unaRespuestaQueTardaDemasiadoCuentaComoFallo() {
        // tope de demora de un nanosegundo: cualquier respuesta llega tarde
        CortacircuitoDeLaIa cortacircuito = new CortacircuitoDeLaIa(3, Duration.ofMillis(60), Duration.ofNanos(1));

        responder(cortacircuito);
        responder(cortacircuito);
        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");
        responder(cortacircuito);

        assertThat(cortacircuito.estado()).as("tres respuestas demasiado lentas son tres fallos").isEqualTo("ABIERTO");
    }

    @Test
    void unaRespuestaBuenaEnMedioLimpiaLaCuentaDeFallos() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();

        fallar(cortacircuito);
        fallar(cortacircuito);
        responder(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);

        // cuatro fallos en total, pero nunca tres SEGUIDOS: la IA sigue respondiendo de vez en cuando
        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");
    }

    @Test
    void alCumplirseElTiempoPasaUnaSolaPeticionDePruebaYSiFuncionaSeCierra() throws Exception {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        fallar(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);
        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");

        Thread.sleep(120); // se cumplen los 60 ms que tenia que quedarse abierto

        CortacircuitoDeLaIa.Llamada prueba = cortacircuito.iniciar();
        assertThat(prueba).as("la peticion de prueba si pasa").isNotNull();
        assertThat(cortacircuito.estado()).isEqualTo("MEDIO_ABIERTO");
        assertThat(cortacircuito.estadoComoNumero()).isEqualTo(1);
        // y es UNA sola: mientras se prueba, el resto sigue yendo al respaldo
        assertThat(cortacircuito.iniciar()).as("solo pasa una de prueba").isNull();

        prueba.exito();

        assertThat(cortacircuito.estado()).as("la IA volvio: se cierra").isEqualTo("CERRADO");
        assertThat(cortacircuito.iniciar()).as("y se vuelve a llamar a la IA con normalidad").isNotNull();
    }

    @Test
    void siLaPeticionDePruebaFallaSeVuelveAAbrir() throws Exception {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        fallar(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);

        Thread.sleep(120);
        CortacircuitoDeLaIa.Llamada prueba = cortacircuito.iniciar();
        assertThat(prueba).isNotNull();
        prueba.fallo(new RoadmapAiException("simulacro: sigue caida"));

        assertThat(cortacircuito.estado()).as("sigue caida: otros 60 ms sin llamarla").isEqualTo("ABIERTO");
        assertThat(cortacircuito.iniciar()).isNull();

        // y pasado el tiempo vuelve a dejar probar: no se queda apagada para siempre
        Thread.sleep(120);
        assertThat(cortacircuito.iniciar()).isNotNull();
    }

    @Test
    void unaRespuestaInvalidaDeLaPeticionDePruebaTambienLaVuelveAAbrir() throws Exception {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        fallar(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);

        Thread.sleep(120);
        CortacircuitoDeLaIa.Llamada prueba = cortacircuito.iniciar();
        assertThat(prueba).isNotNull();
        prueba.respuestaInvalida();

        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");
    }

    @Test
    void reportarDosVecesLaMismaLlamadaNoDesordenaLaCuenta() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();

        for (int i = 0; i < 3; i++) {
            CortacircuitoDeLaIa.Llamada llamada = cortacircuito.iniciar();
            assertThat(llamada).isNotNull();
            llamada.fallo(new RoadmapAiException("simulacro"));
            llamada.exito(); // llega tarde: se ignora, el fallo ya esta contado
        }

        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");
    }

    @Test
    void reiniciarDejaElCortacircuitoComoNuevo() {
        CortacircuitoDeLaIa cortacircuito = cortacircuito();
        fallar(cortacircuito);
        fallar(cortacircuito);
        fallar(cortacircuito);
        assertThat(cortacircuito.estado()).isEqualTo("ABIERTO");

        cortacircuito.reiniciar();

        assertThat(cortacircuito.estado()).isEqualTo("CERRADO");
        assertThat(cortacircuito.estadoComoNumero()).isZero();
        assertThat(cortacircuito.iniciar()).isNotNull();
    }
}
