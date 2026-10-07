package com.growlink.cursos.domain;

// quien armo un roadmap, para no decir "generado por IA" cuando en realidad lo hizo el respaldo
public enum FuenteRoadmap {
    // lo armo Claude y la respuesta paso la validacion contra el catalogo activo
    IA,
    // lo armo el algoritmo de respaldo (sin llave, o la IA fallo o contesto algo invalido)
    RESPALDO
}
