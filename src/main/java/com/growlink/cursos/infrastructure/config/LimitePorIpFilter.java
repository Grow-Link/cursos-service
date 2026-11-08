package com.growlink.cursos.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

// ATRIBUTO: seguridad y disponibilidad. LimiteDeGeneraciones ya pone un tope por PERSONA a lo que cuesta dinero
// (los roadmaps con IA), pero eso no frena a quien no se identifica: un script puede golpear cualquier endpoint
// sin token y dejar al servicio sin hilos ni conexiones para los demas. Este filtro pone un tope por IP a TODAS
// las peticiones, y lo pone antes de la seguridad y de la base de datos, que es donde sale barato rechazarlas.
//
// Mas de `maximo-por-minuto` peticiones de la misma IP dentro de la ventana reciben 429 con Retry-After.
// La cuenta es por ventana fija (una IP solo ocupa un contador, no la lista de sus peticiones): asi un ataque no
// puede hacer crecer la memoria pidiendo mas rapido. Cada replica cuenta por su lado, igual que
// LimiteDeGeneraciones: el servicio sigue sin estado compartido, asi que con N replicas el tope real es de hasta
// N veces el configurado. maximo-por-minuto: 0 lo apaga.
//
// Va despues de SolicitudIdFilter para que la linea del log salga con el X-Request-Id de la peticion: el patron
// del log del proyecto ya lo imprime en cada linea (%X{rid}, ver application.yml), venga del cliente o se haya
// creado aqui.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class LimitePorIpFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimitePorIpFilter.class);

    // La IP de verdad: delante del servicio hay un balanceador, asi que la del socket es la suya, no la del
    // cliente. El primer valor de X-Forwarded-For es el cliente original (los que siguen son los proxies).
    public static final String CABECERA_IP = "X-Forwarded-For";

    // tope de IPs vigiladas a la vez: un techo para que una lluvia de IPs distintas no haga crecer la memoria
    private static final int MAX_IPS = 50_000;

    private final int maximo;
    private final long ventanaMillis;
    private final Clock reloj;
    private final MeterRegistry registro;
    private final ConcurrentHashMap<String, Contador> porIp = new ConcurrentHashMap<>();

    @Autowired
    public LimitePorIpFilter(@Value("${seguridad.limite-ip.maximo-por-minuto:300}") int maximo,
                             @Value("${seguridad.limite-ip.ventana-segundos:60}") int ventanaSegundos,
                             MeterRegistry registro) {
        this(maximo, ventanaSegundos, registro, Clock.systemUTC());
    }

    // el reloj se inyecta para poder probar el paso del tiempo sin esperar de verdad
    LimitePorIpFilter(int maximo, int ventanaSegundos, MeterRegistry registro, Clock reloj) {
        this.maximo = maximo;
        this.ventanaMillis = Math.max(1L, ventanaSegundos) * 1000L;
        this.registro = registro;
        this.reloj = reloj;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (maximo <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        String ip = ipDe(request);
        long esperaSegundos = segundosDeEsperaSiSePaso(ip);
        if (esperaSegundos < 0) {
            filterChain.doFilter(request, response);
            return;
        }
        // una sola linea, con la IP; el X-Request-Id lo pone el patron del log (rid) para todas las lineas
        log.warn("Limite por IP: se rechaza {} {} de la IP {} (mas de {} peticiones en {} s)",
                request.getMethod(), request.getRequestURI(), ip, maximo, ventanaMillis / 1000);
        registro.counter("growlink.http.limite-ip.rechazos").increment();

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(esperaSegundos));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // misma forma que el resto de los errores del servicio (ver GlobalExceptionHandler.ErrorBody)
        response.getWriter().write("{\"message\":\"Demasiadas peticiones desde tu red. Espera "
                + esperaSegundos + " s y vuelve a intentarlo.\"}");
    }

    /** La IP del cliente: el primer valor de X-Forwarded-For si llega, y si no la del socket. */
    static String ipDe(HttpServletRequest request) {
        String reenviada = request.getHeader(CABECERA_IP);
        if (reenviada != null) {
            String primera = reenviada.split(",")[0].trim();
            if (!primera.isEmpty()) {
                return primera;
            }
        }
        String socket = request.getRemoteAddr();
        return socket == null || socket.isBlank() ? "desconocida" : socket;
    }

    /** Anota la peticion de esa IP: -1 si cabe en la ventana, o los segundos que faltan si ya se paso del tope. */
    private long segundosDeEsperaSiSePaso(String ip) {
        if (porIp.size() > MAX_IPS) {
            descartarVentanasViejas();
        }
        long ahora = reloj.millis();
        Contador contador = porIp.computeIfAbsent(ip, clave -> new Contador(ahora));
        synchronized (contador) {
            if (ahora - contador.inicio >= ventanaMillis) {
                contador.inicio = ahora;
                contador.peticiones = 0;
            }
            contador.peticiones++;
            if (contador.peticiones <= maximo) {
                return -1;
            }
            long faltanMillis = contador.inicio + ventanaMillis - ahora;
            return Math.max(1L, (faltanMillis + 999) / 1000);
        }
    }

    // Limpieza aproximada (no hace falta que sea exacta: es un contador de ritmo, no una cuenta de dinero):
    // se sueltan las IPs cuya ventana ya se cerró y que por tanto empezarian de cero igual.
    private void descartarVentanasViejas() {
        long limite = reloj.millis() - ventanaMillis;
        porIp.entrySet().removeIf(entrada -> entrada.getValue().inicio < limite);
    }

    private static final class Contador {
        private long inicio;
        private int peticiones;

        private Contador(long inicio) {
            this.inicio = inicio;
        }
    }
}
