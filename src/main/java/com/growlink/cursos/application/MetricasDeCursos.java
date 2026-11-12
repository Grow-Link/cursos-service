package com.growlink.cursos.application;

import com.growlink.cursos.domain.Categoria;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// ATRIBUTO: observabilidad (negocio). Cuantos cursos se completan, para el tablero de negocio:
//
//   growlink.cursos.completados{via, categoria}
//     via=examen     una persona aprobo el examen del curso (la forma normal de completarlo)
//     via=admin      un ADMIN lo marco como completado a mano (POST /api/cursos/{id}/completar)
//     categoria      el area del curso (el nombre del enum Categoria: son pocas y fijas)
//
// Que cuenta y que no, para que el numero diga lo que parece que dice:
//   - solo un registro NUEVO: presentar otra vez un examen ya aprobado, o completar dos veces, no suma;
//   - solo lo que quedo guardado: se suma cuando la transaccion se confirma, no antes (si se deshace, no cuenta);
//   - lo que siembra el arranque para el usuario de demostracion NO cuenta: no es alguien completando un curso
//     (CursoCatalogoSeeder usa CursoService.completar, que no cuenta; el endpoint del ADMIN usa completarComoAdmin).
//
// Cuenta por replica y desde que arranco; se consulta en /actuator/metrics (solo ADMIN), igual que MetricasDeRoadmap.
@Component
public class MetricasDeCursos {

    public static final String COMPLETADOS = "growlink.cursos.completados";
    public static final String VIA_EXAMEN = "examen";
    public static final String VIA_ADMIN = "admin";

    private final MeterRegistry registro;

    public MetricasDeCursos(MeterRegistry registro) {
        this.registro = registro;
        // todas las combinaciones se registran desde el arranque, para que el tablero vea un 0 y no un "sin datos"
        for (Categoria categoria : Categoria.values()) {
            contador(VIA_EXAMEN, categoria);
            contador(VIA_ADMIN, categoria);
        }
    }

    /** Un curso que quedo completado (un registro nuevo). Se suma cuando se confirma la transaccion en curso. */
    public void cursoCompletado(String via, Categoria categoria) {
        Counter completados = contador(via, categoria);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            completados.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completados.increment();
            }
        });
    }

    private Counter contador(String via, Categoria categoria) {
        return Counter.builder(COMPLETADOS)
                .description("cursos completados: por examen aprobado o marcados por un ADMIN")
                .tag("via", via)
                .tag("categoria", categoria.name())
                .register(registro);
    }
}
