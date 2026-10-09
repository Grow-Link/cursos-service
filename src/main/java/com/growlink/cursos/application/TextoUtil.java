package com.growlink.cursos.application;

import java.text.Normalizer;
import java.util.*;

// utilidades para comparar texto sin que importen las tildes ni las mayusculas
public final class TextoUtil {

    private static final Set<String> PALABRAS_VACIAS = Set.of(
            "quiero", "quisiera", "aprender", "aprendo", "para", "como", "con", "una", "uno", "unos", "unas",
            "los", "las", "del", "que", "por", "mas", "muy", "mis", "mio", "mia", "este", "esta", "esto",
            "ser", "soy", "estoy", "tengo", "poder", "puedo", "hacer", "meses", "anos", "dias", "semanas",
            "nivel", "avanzado", "intermedio", "basico", "principiante", "bien", "mejor", "todo", "toda",
            "sobre", "desde", "hasta", "donde", "cuando", "trabajar", "trabajo", "futuro", "carrera",
            "profesional", "meta", "objetivo", "ademas", "tambien", "pero", "sin", "sus", "han", "hay");

    private TextoUtil() {
    }

    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT);
    }

    // las palabras que de verdad dicen algo de la meta, sin tildes y sin las palabras de relleno
    public static List<String> palabrasClave(String texto) {
        List<String> palabras = new ArrayList<>();
        for (String p : normalizar(texto).split("[^a-z0-9+#]+")) {
            if (p.length() >= 3 && !PALABRAS_VACIAS.contains(p) && !palabras.contains(p)) {
                palabras.add(p);
            }
        }
        return palabras;
    }

    // raiz corta para que "administrar" encuentre "administracion" y "proyectos" encuentre "proyecto"
    public static String raiz(String palabra) {
        return palabra.length() > 5 ? palabra.substring(0, 5) : palabra;
    }
}
