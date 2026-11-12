package com.growlink.cursos.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

// ATRIBUTO: confidencialidad (S3), la parte del servicio. El front manda en X-GL-Clave la llave AES de la peticion
// envuelta con la llave publica de este servicio (ver CifradoDeCanal); si viene, este filtro:
//   1. descifra el cuerpo de la peticion antes de que Spring lo lea (los controladores ven el JSON de siempre)
//   2. deja que la peticion siga su camino normal (autenticacion, autorizacion, controlador)
//   3. cifra la respuesta con la misma llave AES y avisa con X-GL-Cifrado: 1
// Sin X-GL-Clave la peticion pasa tal cual, asi las llamadas entre servicios, Prometheus y Azure (que no cifran)
// siguen funcionando. Con growlink.cifrado.obligatorio=true, lo que no sea de esa lista de excepciones y llegue sin
// cifrar se rechaza con 400. Va justo despues de SolicitudIdFilter y antes de Spring Security.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CifradoFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CifradoFilter.class);

    public static final String CABECERA_CLAVE = "X-GL-Clave";
    public static final String CABECERA_CIFRADO = "X-GL-Cifrado";
    public static final String CABECERA_TIPO = "X-GL-Tipo";
    static final String TIPO_CIFRADO = "application/octet-stream";

    // lo que nunca se cifra: salud y metricas, el handshake del WebSocket, la documentacion, la llave publica
    // (hay que pedirla antes de poder cifrar) y las llamadas internas entre servicios (llevan su propia llave)
    private static final List<String> EXENTAS = List.of("/actuator", "/ws", "/swagger-ui", "/v3/api-docs",
            "/api/seguridad", "/api/interno", "/error");

    private final CifradoDeCanal canal;
    private final MeterRegistry metricas;
    private final boolean obligatorio;

    public CifradoFilter(CifradoDeCanal canal, MeterRegistry metricas,
                         @Value("${growlink.cifrado.obligatorio:false}") boolean obligatorio) {
        this.canal = canal;
        this.metricas = metricas;
        this.obligatorio = obligatorio;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String llaveEnvuelta = request.getHeader(CABECERA_CLAVE);
        if (llaveEnvuelta == null || llaveEnvuelta.isBlank() || esExenta(request)) {
            if (obligatorio && !esExenta(request) && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
                contar("sin_cifrar");
                rechazar(response, "Esta API solo acepta peticiones cifradas.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        byte[] llave;
        HttpServletRequest peticion = request;
        try {
            llave = canal.desenvolver(llaveEnvuelta.trim());
            byte[] cuerpo = request.getInputStream().readAllBytes();
            if (cuerpo.length > 0) {
                String tipo = request.getHeader(CABECERA_TIPO);
                peticion = new PeticionDescifrada(request, canal.descifrar(llave, cuerpo),
                        tipo == null || tipo.isBlank() ? "application/json" : tipo);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // no se detalla la causa a quien llama (no ayuda a un atacante), pero si queda en el log
            log.warn("peticion cifrada rechazada: {} {} ({})", request.getMethod(), request.getRequestURI(),
                    e.getClass().getSimpleName());
            contar("rechazada");
            rechazar(response, "No se pudo descifrar la peticion.");
            return;
        }

        ContentCachingResponseWrapper respuesta = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(peticion, respuesta);
        } finally {
            cifrarRespuesta(respuesta, llave);
        }
        contar("ok");
    }

    private void cifrarRespuesta(ContentCachingResponseWrapper respuesta, byte[] llave) throws IOException {
        byte[] claro = respuesta.getContentAsByteArray();
        if (claro.length == 0) {
            respuesta.copyBodyToResponse();
            return;
        }
        try {
            byte[] cifrado = canal.cifrar(llave, claro);
            String tipoOriginal = respuesta.getContentType();
            HttpServletResponse real = (HttpServletResponse) respuesta.getResponse();
            real.setHeader(CABECERA_CIFRADO, "1");
            if (tipoOriginal != null) {
                real.setHeader(CABECERA_TIPO, tipoOriginal);
            }
            real.setHeader("Cache-Control", "no-store");
            real.setContentType(TIPO_CIFRADO);
            real.setContentLength(cifrado.length);
            real.getOutputStream().write(cifrado);
            real.flushBuffer();
        } catch (GeneralSecurityException e) {
            // cifrar no deberia fallar nunca; si pasa, es mejor un 500 que mandar el cuerpo en claro
            log.error("no se pudo cifrar la respuesta", e);
            HttpServletResponse real = (HttpServletResponse) respuesta.getResponse();
            if (!real.isCommitted()) {
                real.reset();
                real.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static boolean esExenta(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        return EXENTAS.stream().anyMatch(prefijo -> ruta.equals(prefijo) || ruta.startsWith(prefijo + "/"));
    }

    private void contar(String resultado) {
        metricas.counter("growlink_cifrado_peticiones_total", "resultado", resultado).increment();
    }

    private static void rechazar(HttpServletResponse response, String mensaje) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + mensaje + "\"}");
    }

    /** La peticion con su cuerpo ya descifrado y el tipo de contenido original, como si nunca hubiera ido cifrada. */
    private static final class PeticionDescifrada extends HttpServletRequestWrapper {
        private final byte[] cuerpo;
        private final String tipo;

        PeticionDescifrada(HttpServletRequest request, byte[] cuerpo, String tipo) {
            super(request);
            this.cuerpo = cuerpo;
            this.tipo = tipo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream origen = new ByteArrayInputStream(cuerpo);
            return new ServletInputStream() {
                @Override public int read() { return origen.read(); }
                @Override public int read(byte[] b, int off, int len) { return origen.read(b, off, len); }
                @Override public boolean isFinished() { return origen.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { /* lectura sincrona, no aplica */ }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override public String getContentType() { return tipo; }
        @Override public int getContentLength() { return cuerpo.length; }
        @Override public long getContentLengthLong() { return cuerpo.length; }

        @Override
        public String getHeader(String nombre) {
            if ("Content-Type".equalsIgnoreCase(nombre)) return tipo;
            if ("Content-Length".equalsIgnoreCase(nombre)) return String.valueOf(cuerpo.length);
            return super.getHeader(nombre);
        }

        @Override
        public Enumeration<String> getHeaders(String nombre) {
            if ("Content-Type".equalsIgnoreCase(nombre)) return Collections.enumeration(List.of(tipo));
            if ("Content-Length".equalsIgnoreCase(nombre)) {
                return Collections.enumeration(List.of(String.valueOf(cuerpo.length)));
            }
            return super.getHeaders(nombre);
        }
    }
}
