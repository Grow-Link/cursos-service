package com.growlink.cursos.application;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

// ATRIBUTO: disponibilidad (tolerancia a fallos). Cortacircuito delante de la IA que arma los roadmaps: si Claude
// se cae, se demora o contesta cualquier cosa, no tiene sentido seguir preguntandole peticion tras peticion
// (cada intento le cuesta tiempo a la persona que espera y dinero a la cuenta). Se deja de llamar un rato y el
// roadmap lo arma el algoritmo de respaldo, que no sale a internet.
//
// Reglas (todas configurables, ver application.yml):
//   - se abre tras `fallos` fallos SEGUIDOS. Cuenta como fallo cualquiera de los tres: que la llamada reviente,
//     que tarde mas de `demora`, o que conteste algo que no sirve (ver RoadmapService.esPropuestaValida);
//   - queda abierto `espera`: mientras tanto no se llama a la IA, se responde con el respaldo;
//   - al cumplirse ese tiempo deja pasar UNA sola peticion de prueba (medio abierto): si funciona, el circuito se
//     cierra y la IA vuelve a usarse; si falla, se vuelve a abrir otros `espera` segundos.
//
// Se usa la API de Resilience4j a mano (permiso + reporte) en vez de la anotacion @CircuitBreaker porque el
// resultado no se sabe cuando la llamada termina: una respuesta puede llegar sin error y descubrirse invalida
// despues, al validarla contra el catalogo real. Por eso el que llama pide una Llamada y luego la cierra
// diciendo como le fue.
//
// Cada replica tiene su propio cortacircuito (igual que LimiteDeGeneraciones): no hay estado compartido, el
// servicio sigue siendo replicable.
@Component
public class CortacircuitoDeLaIa {

    private static final Logger log = LoggerFactory.getLogger(CortacircuitoDeLaIa.class);
    private static final String NOMBRE = "roadmap-ia";

    private final CircuitBreaker interruptor;
    private final Duration demora;

    @Autowired
    public CortacircuitoDeLaIa(MetricasDeRoadmap metricas,
                               @Value("${roadmap.ia.cortacircuito.fallos:3}") int fallos,
                               @Value("${roadmap.ia.cortacircuito.espera-segundos:60}") int esperaSegundos,
                               @Value("${roadmap.ia.cortacircuito.demora-segundos:10}") int demoraSegundos) {
        this(fallos, Duration.ofSeconds(esperaSegundos), Duration.ofSeconds(demoraSegundos));
        // ATRIBUTO: observabilidad. El estado queda a la vista en /actuator/metrics (solo ADMIN): sin esto, que la
        // IA este apagada seria invisible desde fuera. Se le pasa el bean (no un lambda suelto) porque Micrometer
        // guarda la referencia de la medida en debil y un lambda sin dueno se podria perder.
        metricas.estadoDelCortacircuito(this, c -> c.estadoComoNumero());
    }

    // los valores se inyectan para poder probar sin esperar 60 segundos de verdad
    CortacircuitoDeLaIa(int fallos, Duration espera, Duration demora) {
        this.demora = demora;
        int ventana = Math.max(1, fallos);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // ventana por numero de llamadas y umbral del 100%: se abre solo si las ultimas `ventana` llamadas
                // fallaron TODAS, o sea tras `fallos` fallos seguidos (un exito en medio limpia la cuenta)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(ventana)
                .minimumNumberOfCalls(ventana)
                .failureRateThreshold(100f)
                .waitDurationInOpenState(espera)
                // la peticion de prueba del estado medio abierto: una sola
                .permittedNumberOfCallsInHalfOpenState(1)
                // sin hilo de fondo: el paso de abierto a medio abierto lo hace la primera peticion que llega
                // despues de que se cumplio el tiempo
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        this.interruptor = CircuitBreaker.of(NOMBRE, config);
        this.interruptor.getEventPublisher().onStateTransition(e -> log.warn(
                "Cortacircuito de la IA: {} -> {}", e.getStateTransition().getFromState(),
                e.getStateTransition().getToState()));
    }

    /**
     * Pide permiso para llamar a la IA. Devuelve la llamada en curso, o null si el circuito esta abierto y por
     * tanto NO se debe llamar a la IA (el roadmap lo tiene que armar el respaldo). Toda llamada que se abre se
     * cierra despues con exito(), fallo(...) o respuestaInvalida(): si no, el estado medio abierto se quedaria
     * esperando para siempre su peticion de prueba.
     */
    public Llamada iniciar() {
        if (!interruptor.tryAcquirePermission()) {
            return null;
        }
        return new Llamada();
    }

    /** CERRADO (se usa la IA), MEDIO_ABIERTO (peticion de prueba) o ABIERTO (solo respaldo). */
    public String estado() {
        return switch (interruptor.getState()) {
            case CLOSED -> "CERRADO";
            case HALF_OPEN -> "MEDIO_ABIERTO";
            case OPEN, FORCED_OPEN -> "ABIERTO";
            default -> interruptor.getState().name();
        };
    }

    /** La misma cosa como numero, para la medida de Micrometer: 0 cerrado, 1 medio abierto, 2 abierto. */
    double estadoComoNumero() {
        return switch (interruptor.getState()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
            default -> -1;
        };
    }

    /** Vuelve a dejarlo cerrado y sin historia. Para las pruebas y para poder reactivar la IA a mano. */
    public void reiniciar() {
        interruptor.reset();
    }

    /**
     * Una llamada a la IA en curso: ya tiene el permiso del cortacircuito y espera que se le diga como termino.
     * Reportar dos veces no hace nada (se ignora), asi un camino de error que pasa por dos sitios no desordena
     * la cuenta de fallos.
     */
    public final class Llamada {

        private final long inicio = System.nanoTime();
        private boolean cerrada;

        private Llamada() {
        }

        /** Cuanto lleva (o cuanto llevaba al cerrarse), para las medidas de duracion. */
        public long nanos() {
            return System.nanoTime() - inicio;
        }

        /** La IA contesto. Si tardo mas que el tope de demora, para el cortacircuito igual cuenta como fallo. */
        public void exito() {
            long nanos = nanos();
            if (!marcar()) {
                return;
            }
            if (demora != null && !demora.isZero() && nanos >= demora.toNanos()) {
                long ms = TimeUnit.NANOSECONDS.toMillis(nanos);
                log.warn("La IA contesto, pero tardo {} ms (el tope es {} ms): cuenta como fallo", ms, demora.toMillis());
                interruptor.onError(nanos, TimeUnit.NANOSECONDS,
                        new RoadmapAiException("La IA tardo " + ms + " ms en contestar"));
                return;
            }
            interruptor.onSuccess(nanos, TimeUnit.NANOSECONDS);
        }

        /** La llamada reventó (red, tiempo limite, respuesta mal formada...). */
        public void fallo(Throwable causa) {
            long nanos = nanos();
            if (marcar()) {
                interruptor.onError(nanos, TimeUnit.NANOSECONDS, causa);
            }
        }

        /**
         * La IA contesto sin error, pero lo que contesto no sirve (cursos que no existen, repetidos, dados de
         * baja, prerequisitos inventados...). Para el cortacircuito es un fallo igual que si no hubiera contestado.
         */
        public void respuestaInvalida() {
            fallo(new RoadmapAiException("La IA contesto algo que no es una ruta valida del catalogo"));
        }

        private synchronized boolean marcar() {
            if (cerrada) {
                return false;
            }
            cerrada = true;
            return true;
        }
    }
}
