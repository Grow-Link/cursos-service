package com.growlink.cursos.application;

import com.growlink.cursos.domain.FuenteRoadmap;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// ATRIBUTO: observabilidad. Cinco medidas, y solo las que contestan una pregunta de verdad sobre el roadmap:
//   growlink.roadmap.generaciones{fuente=IA|RESPALDO}       cuantos roadmaps salieron de la IA y cuantos del respaldo
//   growlink.roadmap.cache{resultado=acierto|fallo}         cuantas veces se evito una llamada a la IA
//   growlink.roadmap.ia.llamadas{resultado=...}             como le fue a las llamadas a Claude (y ia.duracion: cuanto tardaron)
//   growlink.roadmap.ia.propuestas{resultado=...}           cuantas propuestas de la IA paso el servidor y por que rechazo el resto
//   growlink.roadmap.limite.rechazos                        cuantas peticiones frenó el tope por persona
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
}
