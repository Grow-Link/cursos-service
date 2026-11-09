package com.growlink.cursos.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// ATRIBUTO: desempeno y disponibilidad. Sin esto, CADA peticion al servicio seria tambien una peticion a
// usuarios-service: se le dobla el trafico a un servicio del que ya dependemos, y la latencia de cada llamada
// nuestra pasa a incluir la suya. Con la identidad guardada un ratito, una rafaga de peticiones del mismo usuario
// cuesta una sola consulta.
//
// Y de paso tapa un hueco de disponibilidad: si usuarios-service se cae, quien ya tiene su identidad guardada
// sigue trabajando hasta que se le venza (ver ClienteDeUsuariosService, que consulta la cache ANTES de salir a la
// red).
//
// Dos topes, los dos a proposito:
//   - `vigencia` (AUTH_IDENTITY_CACHE_SECONDS, 60 s por defecto): es el retraso con el que se entera este
//     servicio de un cambio de rol en usuarios-service. Corto porque un rol es un permiso: si a alguien le
//     quitan PUBLICADOR, no puede seguir publicando un dia entero.
//   - el `exp` del token: una entrada NUNCA vive mas que el token que la trajo. Si no, un token ya vencido
//     seguiria teniendo identidad guardada aqui y la cache se convertiria en una forma de alargarlo.
// Mas `maxEntradas`, para que una lluvia de tokens distintos no haga crecer la memoria sin freno.
//
// La clave no es el token sino su huella (ver ClienteDeUsuariosService.huella): un token es una credencial y no
// tiene por que quedar guardado en memoria entero.
class CacheDeIdentidades {

    private final Duration vigencia;
    private final int maxEntradas;
    private final Clock reloj;
    private final ConcurrentHashMap<String, Entrada> entradas = new ConcurrentHashMap<>();

    CacheDeIdentidades(Duration vigencia, int maxEntradas, Clock reloj) {
        this.vigencia = vigencia;
        this.maxEntradas = Math.max(1, maxEntradas);
        this.reloj = reloj;
    }

    /** La identidad guardada para esa huella, si todavia esta vigente. */
    Optional<IdentidadDeUsuario> buscar(String huella) {
        Entrada entrada = entradas.get(huella);
        if (entrada == null) {
            return Optional.empty();
        }
        if (!entrada.vigenteEn(reloj.instant())) {
            entradas.remove(huella, entrada);
            return Optional.empty();
        }
        return Optional.of(entrada.identidad());
    }

    /** Guarda la identidad hasta que se cumpla la vigencia o venza el token, lo que pase primero. */
    void guardar(String huella, IdentidadDeUsuario identidad, Instant expiracionDelToken) {
        Instant ahora = reloj.instant();
        Instant hasta = ahora.plus(vigencia);
        if (expiracionDelToken != null && expiracionDelToken.isBefore(hasta)) {
            hasta = expiracionDelToken;
        }
        if (!hasta.isAfter(ahora)) {
            return; // no vale la pena guardar algo que ya nace vencido
        }
        if (entradas.size() >= maxEntradas) {
            hacerSitio(ahora);
        }
        entradas.put(huella, new Entrada(identidad, hasta));
    }

    // primero lo que ya vencio (que es gratis soltar); si aun asi no cabe, las entradas que vencen antes, que son
    // las que menos se pierde por soltar. Aproximado a proposito: es una cache, no una cuenta de dinero
    private void hacerSitio(Instant ahora) {
        entradas.values().removeIf(entrada -> !entrada.vigenteEn(ahora));
        int sobran = entradas.size() - maxEntradas + 1;
        if (sobran <= 0) {
            return;
        }
        entradas.entrySet().stream()
                .sorted(Comparator.comparing(entrada -> entrada.getValue().hasta()))
                .limit(sobran)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entradas::remove);
    }

    int tamano() {
        return entradas.size();
    }

    private record Entrada(IdentidadDeUsuario identidad, Instant hasta) {

        boolean vigenteEn(Instant ahora) {
            return ahora.isBefore(hasta);
        }
    }
}
