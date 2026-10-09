package com.growlink.cursos.application;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

// revisa que la meta parezca una frase de verdad antes de gastarle una llamada a la IA.
// No juzga si se puede cumplir (eso lo decide el catalogo y la IA), solo descarta lo que no es una frase
@Component
public class MetaValidator {

    public static final int MIN_CARACTERES = 12;
    public static final int MAX_CARACTERES = 280;

    // tiras de teclas seguidas: asdf, qwerty, zxcv...
    private static final List<String> TECLADO = List.of("asdf", "sdfg", "dfgh", "fghj", "ghjk", "hjkl",
            "qwer", "wert", "erty", "rtyu", "tyui", "yuio", "uiop", "zxcv", "xcvb", "cvbn", "vbnm", "qaz", "wsx");

    public void validar(String metas) {
        String texto = metas == null ? "" : metas.trim().replaceAll("\\s+", " ");
        if (texto.length() < MIN_CARACTERES) {
            throw new MetaInvalidaException("Cuéntanos tu meta con un poco más de detalle (al menos " + MIN_CARACTERES
                    + " caracteres). Por ejemplo: «Quiero aprender Python para analizar datos».");
        }
        if (texto.length() > MAX_CARACTERES) {
            throw new MetaInvalidaException("Tu meta es muy larga, resúmela en máximo " + MAX_CARACTERES + " caracteres.");
        }

        String normalizado = TextoUtil.normalizar(texto);
        long letras = normalizado.chars().filter(Character::isLetter).count();
        if (letras < normalizado.replace(" ", "").length() * 0.75) {
            throw new MetaInvalidaException("Tu meta tiene demasiados números o símbolos. Escríbela con tus palabras.");
        }

        String[] palabras = normalizado.split("[^a-z0-9+#]+");
        long palabrasReales = Arrays.stream(palabras).filter(p -> p.length() >= 2).count();
        if (palabrasReales < 3) {
            throw new MetaInvalidaException("Escribe tu meta en una frase de al menos 3 palabras. Por ejemplo: "
                    + "«Quiero liderar proyectos de software».");
        }

        long sospechosas = Arrays.stream(palabras).filter(this::parecePalabraAlAzar).count();
        if (sospechosas * 2 >= palabras.length) {
            throw new MetaInvalidaException("No logramos entender tu meta. Escríbela con palabras completas, por "
                    + "ejemplo: «Quiero dominar Python para trabajar con datos».");
        }
    }

    // una palabra sin vocales, con una letra repetida muchas veces o que es una tira de teclas seguidas
    private boolean parecePalabraAlAzar(String palabra) {
        if (palabra.length() < 3) {
            return false;
        }
        boolean tieneVocal = palabra.chars().anyMatch(c -> "aeiou".indexOf(c) >= 0);
        if (!tieneVocal) {
            return true;
        }
        if (palabra.matches(".*(.)\\1{2,}.*")) {
            return true;
        }
        return TECLADO.stream().anyMatch(palabra::contains);
    }
}
