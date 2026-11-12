package com.growlink.cursos.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.Base64;

// ATRIBUTO: confidencialidad (S3). El cifrado va ENCIMA de HTTPS, no en su lugar: el front cifra el cuerpo de
// cada peticion con una llave AES-256-GCM que crea solo para esa peticion, esa llave viaja envuelta con la llave
// publica del servicio (RSA-OAEP con SHA-256), el servicio la desenvuelve con su llave privada, descifra,
// responde y cifra la respuesta con la MISMA llave AES (con otro IV). Asi la llave no se reutiliza entre
// peticiones y la privada nunca sale del servicio.
//
// La llave privada viene de la configuracion (growlink.cifrado.clave-privada, PKCS#8 en base64) para que todas las
// instancias del servicio usen la misma. Si no se configura se crea una al arrancar: sirve para desarrollo, pero
// con varias instancias cada una tendria una distinta y una peticion cifrada para una fallaria en la otra.
@Component
public class CifradoDeCanal {

    private static final Logger log = LoggerFactory.getLogger(CifradoDeCanal.class);

    public static final int BYTES_IV = 12;
    private static final int BITS_ETIQUETA = 128;
    private static final int BYTES_LLAVE_AES = 32;
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final SecureRandom azar = new SecureRandom();
    private final String privadaConfigurada;
    private PrivateKey privada;
    private String publicaBase64;

    public CifradoDeCanal(@Value("${growlink.cifrado.clave-privada:}") String privadaConfigurada) {
        this.privadaConfigurada = privadaConfigurada == null ? "" : privadaConfigurada.trim();
    }

    @PostConstruct
    void iniciar() throws GeneralSecurityException {
        if (privadaConfigurada.isEmpty()) {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            KeyPair par = generador.generateKeyPair();
            privada = par.getPrivate();
            publicaBase64 = Base64.getEncoder().encodeToString(par.getPublic().getEncoded());
            log.warn("growlink.cifrado.clave-privada no esta configurada: se creo una llave temporal. "
                    + "Con varias instancias hay que configurar la misma llave en todas");
            return;
        }
        KeyFactory fabrica = KeyFactory.getInstance("RSA");
        privada = fabrica.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privadaConfigurada)));
        // la publica se deduce de la privada (modulo y exponente publico), no hace falta configurarla aparte
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privada;
        PublicKey publica = fabrica.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        publicaBase64 = Base64.getEncoder().encodeToString(publica.getEncoded());
    }

    /** La llave publica (X.509 / SPKI en base64) que el front usa para envolver la llave de cada peticion. */
    public String clavePublica() {
        return publicaBase64;
    }

    /** Desenvuelve la llave AES que mando el front. Lanza si no se pudo (llave ajena, corrupta o de otro largo). */
    public byte[] desenvolver(String llaveEnvueltaBase64) throws GeneralSecurityException {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, privada, OAEP_SHA256);
        byte[] llave = rsa.doFinal(Base64.getDecoder().decode(llaveEnvueltaBase64));
        if (llave.length != BYTES_LLAVE_AES) {
            throw new GeneralSecurityException("la llave AES debe ser de 256 bits");
        }
        return llave;
    }

    /** Descifra IV (12 bytes) + texto cifrado con etiqueta GCM; si alguien lo altero, lanza. */
    public byte[] descifrar(byte[] llave, byte[] ivMasCifrado) throws GeneralSecurityException {
        if (ivMasCifrado.length < BYTES_IV + BITS_ETIQUETA / 8) {
            throw new GeneralSecurityException("el cuerpo cifrado es demasiado corto");
        }
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(llave, "AES"),
                new GCMParameterSpec(BITS_ETIQUETA, ivMasCifrado, 0, BYTES_IV));
        return aes.doFinal(ivMasCifrado, BYTES_IV, ivMasCifrado.length - BYTES_IV);
    }

    /** Cifra con un IV nuevo al azar y devuelve IV + texto cifrado. */
    public byte[] cifrar(byte[] llave, byte[] claro) throws GeneralSecurityException {
        byte[] iv = new byte[BYTES_IV];
        azar.nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(llave, "AES"), new GCMParameterSpec(BITS_ETIQUETA, iv));
        byte[] cifrado = aes.doFinal(claro);
        byte[] salida = Arrays.copyOf(iv, BYTES_IV + cifrado.length);
        System.arraycopy(cifrado, 0, salida, BYTES_IV, cifrado.length);
        return salida;
    }
}
