package com.growlink.cursos.application;

import com.growlink.cursos.domain.FuenteRoadmap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

// ATRIBUTO: observabilidad. Siete medidas, y solo las que contestan una pregunta de verdad sobre el roadmap:
//   growlink.roadmap.generaciones{fuente=IA|RESPALDO}       cuantos roadmaps salieron de la IA y cuantos del respaldo
//   growlink.roadmap.cache{resultado=acierto|fallo}         cuantas veces se evito una llamada a la IA
//   growlink.roadmap.ia.llamadas{resultado=...}             como le fue a las llamadas a Claude (y ia.duracion: cuanto tardaron)
//   growlink.roadmap.ia.propuestas{resultado=...}           cuantas propuestas de la IA paso el servidor y por que rechazo el resto
//   growlink.roadmap.limite.rechazos                        cuantas peticiones frenó el tope por persona
//   growlink.roadmap.ia.cortacircuito.estado                0 cerrado (se usa la IA), 1 medio abierto, 2 abierto
//   growlink.roadmap.ia.cortacircuito.rechazos              cuantas veces no se llamo a la IA por estar abierto
// Se consultan en /actuator/metrics/<nombre> (solo ADMIN: son datos de operacion). Cuentan por replica.
@Component
public class MetricasDeRoadmap {

    private final MeterRegistry registro;

    public MetricasDeRoadmap(MeterRegistry registro) {
        this.registro = registro;
    }

    public void generacion(FuenteRoadmap fuente) {
        registro.counter("growlink.roadmap.generaciones", "fuente", fuente.name()).increment();
    }

    public void cache(boolean acierto) {
        registro.counter("growlink.roadmap.cache", "resultado", acierto ? "acierto" : "fallo").increment();
    }

    /** resultado: respondio, fallo o no_viable (la IA dijo que la meta no se puede cumplir con el catalogo). */
    public void llamadaIa(String resultado, long nanos) {
        registro.counter("growlink.roadmap.ia.llamadas", "resultado", resultado).increment();
        registro.timer("growlink.roadmap.ia.duracion").record(nanos, TimeUnit.NANOSECONDS);
    }

    /** resultado: aceptada, corregida (aceptada tras pedirle que cuadrara el tiempo), excede_horas o invalida. */
    public void propuestaDeLaIa(String resultado) {
        registro.counter("growlink.roadmap.ia.propuestas", "resultado", resultado).increment();
    }

    public void limiteRechazado() {
        registro.counter("growlink.roadmap.limite.rechazos").increment();
    }

    /**
     * Deja a la vista el estado del cortacircuito de la IA. Se le pasa la fuente (el bean del cortacircuito) y
     * como leerle el estado, no un valor: una medida de tipo gauge se consulta cuando alguien la mira, no cuando
     * se registra. Ver CortacircuitoDeLaIa.
     */
    public <T> void estadoDelCortacircuito(T fuente, ToDoubleFunction<T> estado) {
        Gauge.builder("growlink.roadmap.ia.cortacircuito.estado", fuente, estado)
                .description("0 cerrado (se usa la IA), 1 medio abierto (peticion de prueba), 2 abierto (solo respaldo)")
                .register(registro);
    }

    /** Una peticion que no llego a la IA porque el cortacircuito estaba abierto: la armo el respaldo. */
    public void cortacircuitoRechazo() {
        registro.counter("growlink.roadmap.ia.cortacircuito.rechazos").increment();
    }
}
