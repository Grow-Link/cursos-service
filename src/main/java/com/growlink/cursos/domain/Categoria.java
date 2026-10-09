package com.growlink.cursos.domain;

// las areas de la plataforma. etiqueta() es el nombre que ve la persona, el nombre del enum es lo que
// viaja por la API y se guarda en la base
public enum Categoria {
    INGENIERIA_SISTEMAS("Ingeniería de Sistemas"),
    INGENIERIA_CIVIL("Ingeniería Civil"),
    INGENIERIA_INDUSTRIAL("Ingeniería Industrial"),
    INGENIERIA_ELECTRONICA("Ingeniería Electrónica"),
    INGENIERIA_MECANICA("Ingeniería Mecánica"),
    INGENIERIA_AMBIENTAL("Ingeniería Ambiental"),
    MATEMATICAS("Matemáticas"),
    ADMINISTRACION_EMPRESAS("Administración de Empresas"),
    IDIOMAS("Idiomas"),
    DERECHO("Derecho");

    private final String etiqueta;

    Categoria(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }
}
