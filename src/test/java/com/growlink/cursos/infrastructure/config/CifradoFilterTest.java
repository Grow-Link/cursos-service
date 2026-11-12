package com.growlink.cursos.infrastructure.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

// ATRIBUTO confidencialidad (S3): el filtro solo, sin Spring ni base de datos. Hace de "controlador" un eco que
// devuelve lo que le llega, para ver que lo que llega es el JSON descifrado y lo que sale va cifrado. Esta misma
// prueba esta en los tres servicios (el filtro es el mismo), asi cualquiera que lo cambie lo nota en su repo.
class CifradoFilterTest {

    private static final OAEPParameterSpec OAEP =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private CifradoDeCanal canal;
    private CifradoFilter filtro;
    private CifradoFilter filtroObligatorio;

    @BeforeEach
    void preparar() throws Exception {
        canal = new CifradoDeCanal("");
        canal.iniciar();
        filtro = new CifradoFilter(canal, new SimpleMeterRegistry(), false);
        filtroObligatorio = new CifradoFilter(canal, new SimpleMeterRegistry(), true);
    }

    @Test
    void elControladorVeElJsonClaroYLaRespuestaSaleCifrada() throws Exception {
        SecretKey aes = nuevaLlave();
        MockHttpServletRequest peticion = cifrada(aes, "POST", "/api/algo", "{\"hola\":\"mundo\"}");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AtomicBoolean vioClaro = new AtomicBoolean();

        filtro.doFilter(peticion, respuesta, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws java.io.IOException {
                byte[] cuerpo = req.getInputStream().readAllBytes();
                vioClaro.set("{\"hola\":\"mundo\"}".equals(new String(cuerpo, StandardCharsets.UTF_8))
                        && "application/json".equals(req.getContentType()));
                res.setContentType("application/json");
                res.getOutputStream().write(cuerpo);
            }
        });

        assertThat(vioClaro).isTrue();
        assertThat(respuesta.getHeader("X-GL-Cifrado")).isEqualTo("1");
        assertThat(respuesta.getHeader("X-GL-Tipo")).isEqualTo("application/json");
        byte[] cifrada = respuesta.getContentAsByteArray();
        assertThat(new String(cifrada, StandardCharsets.ISO_8859_1)).doesNotContain("mundo");
        assertThat(descifrar(aes, cifrada)).isEqualTo("{\"hola\":\"mundo\"}");
    }

    @Test
    void unCuerpoAlteradoSeRechazaSinLlegarAlControlador() throws Exception {
        SecretKey aes = nuevaLlave();
        MockHttpServletRequest peticion = cifrada(aes, "POST", "/api/algo", "{\"a\":1}");
        byte[] cuerpo = peticion.getContentAsByteArray();
        cuerpo[5] ^= 0x01;
        peticion.setContent(cuerpo);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AtomicBoolean llego = new AtomicBoolean();

        filtro.doFilter(peticion, respuesta, (req, res) -> llego.set(true));

        assertThat(llego).isFalse();
        assertThat(respuesta.getStatus()).isEqualTo(400);
    }

    @Test
    void sinLlaveLaPeticionPasaTalCual() throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/api/algo");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, (req, res) -> res.getWriter().write("claro"));

        assertThat(respuesta.getContentAsString()).isEqualTo("claro");
        assertThat(respuesta.getHeader("X-GL-Cifrado")).isNull();
    }

    @Test
    void enModoObligatorioLoQueLlegaSinCifrarSeRechazaSalvoLasExcepciones() throws Exception {
        for (String ruta : new String[] {"/actuator/health", "/actuator/prometheus", "/api/seguridad/clave-publica",
                "/api/interno/algo", "/ws", "/v3/api-docs"}) {
            MockHttpServletResponse respuesta = new MockHttpServletResponse();
            filtroObligatorio.doFilter(new MockHttpServletRequest("GET", ruta), respuesta,
                    (req, res) -> res.getWriter().write("ok"));
            assertThat(respuesta.getContentAsString()).as(ruta).isEqualTo("ok");
        }

        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AtomicBoolean llego = new AtomicBoolean();
        filtroObligatorio.doFilter(new MockHttpServletRequest("GET", "/api/cursos"), respuesta, (req, res) -> llego.set(true));

        assertThat(llego).isFalse();
        assertThat(respuesta.getStatus()).isEqualTo(400);
    }

    @Test
    void lasRespuestasSinCuerpoNoSeCifran() throws Exception {
        SecretKey aes = nuevaLlave();
        MockHttpServletRequest peticion = cifrada(aes, "GET", "/api/algo", null);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));

        assertThat(respuesta.getStatus()).isEqualTo(204);
        assertThat(respuesta.getContentAsByteArray()).isEmpty();
    }

    // ---- lo mismo que hace el front con Web Crypto ----

    private MockHttpServletRequest cifrada(SecretKey aes, String metodo, String ruta, String cuerpo) throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest(metodo, ruta);
        peticion.addHeader("X-GL-Clave", envolver(aes));
        if (cuerpo != null) {
            peticion.addHeader("X-GL-Tipo", "application/json");
            peticion.setContentType("application/octet-stream");
            peticion.setContent(cifrar(aes, cuerpo));
        }
        return peticion;
    }

    private static SecretKey nuevaLlave() throws Exception {
        KeyGenerator generador = KeyGenerator.getInstance("AES");
        generador.init(256);
        return generador.generateKey();
    }

    private String envolver(SecretKey aes) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(canal.clavePublica()))), OAEP);
        return Base64.getEncoder().encodeToString(rsa.doFinal(aes.getEncoded()));
    }

    private static byte[] cifrar(SecretKey aes, String texto) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        byte[] salida = Arrays.copyOf(iv, 12 + ct.length);
        System.arraycopy(ct, 0, salida, 12, ct.length);
        return salida;
    }

    private static String descifrar(SecretKey aes, byte[] ivMasCifrado) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, ivMasCifrado, 0, 12));
        return new String(c.doFinal(ivMasCifrado, 12, ivMasCifrado.length - 12), StandardCharsets.UTF_8);
    }
}
