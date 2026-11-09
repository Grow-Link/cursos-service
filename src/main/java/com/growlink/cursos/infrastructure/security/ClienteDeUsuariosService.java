package com.growlink.cursos.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.growlink.cursos.infrastructure.config.SolicitudIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

// Le pregunta a usuarios-service quien es quien llama. Es la pieza que hace que el modo oidc sirva con tokens
// REALES de Microsoft Entra: el `sub` de Entra es un texto opaco (no un numero) y el rol de GrowLink no esta en
// el token, asi que la identidad que este servicio necesita (usuarioId numerico + rol) tiene que salir de quien
// es su dueno, que es usuarios-service.
//
// CONTRATO: GET {USUARIOS_SERVICE_URL}/api/usuarios/me con el MISMO token que llego aqui (se reenvia tal cual,
// no se firma nada nuevo: usuarios-service lo valida por su cuenta). Devuelve MeResponse; los nombres de sus
// campos estan todos en RespuestaMe, aqui abajo, para que ajustarlos sea cambiar una sola clase.
//
// Como se trata cada final posible, que es lo que de verdad importa aqui:
//   - responde bien           -> esa es la identidad, y se guarda un rato en la cache
//   - responde 401            -> 401 aqui: usuarios-service no reconoce ese token, asi que no hay nada que hacer
//   - responde otro 4xx       -> tambien 401: el token es valido pero no se puede resolver a un usuario de
//                                GrowLink (p.ej. 404, alguien que se autentico con Microsoft pero no existe
//                                aqui). Reintentar no lo va a arreglar, asi que no es un 503
//   - 5xx, timeout o no abre  -> 503 (IdentidadNoDisponibleException). NUNCA se acepta el token "a medias" ni se
//                                cae a leer los claims: eso seria dejar que el propio token diga su rol
// En los tres rechazos queda el motivo en el log; el X-Request-Id sale solo, en cada linea, por el patron del log
// del proyecto. Ademas se reenvia ese mismo X-Request-Id a usuarios-service, para poder seguir una peticion de un
// servicio al otro.
public class ClienteDeUsuariosService {

    private static final Logger log = LoggerFactory.getLogger(ClienteDeUsuariosService.class);

    static final String RUTA_ME = "/api/usuarios/me";

    // Cortos a proposito: esta llamada esta en el camino de CADA peticion, asi que si usuarios-service se pone
    // lento, lo que no puede pasar es que este servicio se quede esperandolo y se lleve los hilos con el.
    private static final Duration TIEMPO_DE_CONEXION = Duration.ofSeconds(2);
    private static final Duration TIEMPO_DE_LECTURA = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final String urlBase;
    private final CacheDeIdentidades cache;

    public ClienteDeUsuariosService(String urlBase, Duration vigenciaCache, int maxEntradasCache) {
        this(restClientPorDefecto(), urlBase, vigenciaCache, maxEntradasCache, Clock.systemUTC());
    }

    // el reloj y el cliente HTTP se inyectan para poder probar la cache y los errores sin esperar ni salir a la red
    ClienteDeUsuariosService(RestClient restClient, String urlBase, Duration vigenciaCache, int maxEntradasCache,
                             Clock reloj) {
        this.restClient = restClient;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.cache = new CacheDeIdentidades(vigenciaCache, maxEntradasCache, reloj);
    }

    private static RestClient restClientPorDefecto() {
        // mismo patron que ClaudeRoadmapAiClient: RestClient con tiempos limite puestos a mano
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(TIEMPO_DE_CONEXION);
        fabrica.setReadTimeout(TIEMPO_DE_LECTURA);
        return RestClient.builder().requestFactory(fabrica).build();
    }

    /**
     * Quien es el dueno de este token. Se consulta la cache primero (asi una rafaga del mismo usuario cuesta una
     * sola llamada, y si usuarios-service se cayo pero su identidad sigue guardada, puede seguir trabajando).
     *
     * @throws InvalidBearerTokenException      si usuarios-service no reconoce el token (-> 401)
     * @throws IdentidadNoDisponibleException   si usuarios-service no se pudo consultar (-> 503)
     */
    public IdentidadDeUsuario identidadDe(Jwt token) {
        String huella = huella(token.getTokenValue());
        Optional<IdentidadDeUsuario> guardada = cache.buscar(huella);
        if (guardada.isPresent()) {
            return guardada.get();
        }
        IdentidadDeUsuario identidad = consultar(token);
        cache.guardar(huella, identidad, token.getExpiresAt());
        return identidad;
    }

    private IdentidadDeUsuario consultar(Jwt token) {
        try {
            RespuestaMe respuesta = restClient.get()
                    .uri(urlBase + RUTA_ME)
                    .header("Authorization", "Bearer " + token.getTokenValue())
                    .headers(cabeceras -> {
                        String idDeLaPeticion = MDC.get(SolicitudIdFilter.CLAVE_MDC);
                        if (idDeLaPeticion != null) {
                            cabeceras.set(SolicitudIdFilter.CABECERA, idDeLaPeticion);
                        }
                    })
                    .retrieve()
                    .body(RespuestaMe.class);

            if (respuesta == null || respuesta.usuarioId() == null) {
                // contesto 200 pero sin lo unico que se le pidio: no hay identidad, y es un problema del contrato
                log.error("usuarios-service contesto sin usuarioId en {}: el contrato de MeResponse no cuadra",
                        RUTA_ME);
                throw new IdentidadNoDisponibleException("usuarios-service no devolvio el usuarioId");
            }
            return new IdentidadDeUsuario(respuesta.usuarioId(), respuesta.rol());

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("usuarios-service no reconoce el token (401): se rechaza la peticion");
            throw new InvalidBearerTokenException("usuarios-service no reconoce este token");
        } catch (HttpClientErrorException e) {
            // 403, 404, 400...: el token puede estar bien, pero no se resuelve a un usuario de GrowLink
            log.warn("usuarios-service respondio {} al resolver la identidad: se rechaza la peticion",
                    e.getStatusCode());
            throw new InvalidBearerTokenException("usuarios-service no pudo resolver la identidad de este token");
        } catch (RestClientException e) {
            // 5xx, timeout de lectura, conexion rechazada, DNS... todo lo que es "no se pudo preguntar"
            log.error("No se pudo consultar la identidad en usuarios-service ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new IdentidadNoDisponibleException("No se pudo consultar la identidad en usuarios-service", e);
        }
    }

    /**
     * La clave de la cache: SHA-256 del token, no el token. Asi la credencial no queda guardada entera en memoria
     * (ni aparece como clave en un volcado de memoria) y la clave sigue siendo unica por token.
     */
    static String huella(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tendria que existir en cualquier JVM", e);
        }
    }

    int entradasEnCache() {
        return cache.tamano();
    }

    /**
     * La respuesta de GET /api/usuarios/me. ESTE es el unico sitio con los nombres de los campos del contrato:
     * si usuarios-service los cambia, se ajusta aqui y nada mas.
     * <p>
     * MeResponse de usuarios-service trae hoy (usuarioId, rol, rolElegido, correo, nombre); aqui solo se usan los
     * dos primeros, que son lo que este servicio necesita. Lo que sobre se ignora a proposito, para que agregar
     * un campo alla no rompa esto.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaMe(Long usuarioId, String rol) {
    }
}
