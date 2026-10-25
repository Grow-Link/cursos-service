package com.growlink.cursos.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

// ATRIBUTO: seguridad y costo. Una persona no puede pedir roadmaps sin parar: cada uno puede costar una llamada a la
// IA, y sin tope un script (o un clic repetido) podria gastar la cuenta o dejar a los demas esperando.
// Ventana deslizante: en cualquier tramo de `ventana` caben como maximo `maximo` peticiones por persona. Si se pasa,
// se avisa cuantos segundos faltan para que se libere un cupo. Cada replica cuenta por su lado (no hay estado
// compartido a proposito: sigue siendo un servicio sin estado que se puede replicar), asi que con N replicas el tope
// real es de hasta N veces el configurado. maximo <= 0 lo apaga.
@Component
public class LimiteDeGeneraciones {

    private final int maximo;
    private final Duration ventana;
    private final Clock reloj;
    private final ConcurrentHashMap<Long, Deque<Instant>> intentosPorPersona = new ConcurrentHashMap<>();

    @Autowired
    public LimiteDeGeneraciones(@Value("${roadmap.limite.generaciones:10}") int maximo,
                                @Value("${roadmap.limite.ventana-minutos:10}") int ventanaMinutos) {
        this(maximo, Duration.ofMinutes(ventanaMinutos), Clock.systemUTC());
    }

    // el reloj se inyecta para poder probar el paso del tiempo sin esperar de verdad
    LimiteDeGeneraciones(int maximo, Duration ventana, Clock reloj) {
        this.maximo = maximo;
        this.ventana = ventana;
        this.reloj = reloj;
    }

    /** Anota un intento de la persona; si ya gasto todos sus cupos de la ventana lanza LimiteExcedidoException. */
    public void registrar(Long usuarioId) {
        if (maximo <= 0) {
            return;
        }
        Deque<Instant> intentos = intentosPorPersona.computeIfAbsent(usuarioId, id -> new ArrayDeque<>());
        synchronized (intentos) {
            Instant ahora = reloj.instant();
            Instant desde = ahora.minus(ventana);
            while (!intentos.isEmpty() && !intentos.peekFirst().isAfter(desde)) {
                intentos.pollFirst();
            }
            if (intentos.size() >= maximo) {
                long espera = Duration.between(ahora, intentos.peekFirst().plus(ventana)).toSeconds() + 1;
                throw new LimiteExcedidoException(Math.max(1, espera));
            }
            intentos.addLast(ahora);
        }
    }
}
