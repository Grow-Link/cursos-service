package com.growlink.cursos.adapter.web.dto;

import com.growlink.cursos.domain.Categoria;

import java.util.List;

// una area de la plataforma con lo que de verdad hay en ella ahora mismo, para mostrarsela a la persona
// antes de pedirle su meta (no es un dato escrito a mano, sale de los cursos activos)
public record AreaResumenResponse(Categoria area, String etiqueta, long cursos, int horasTotales,
                                   long principiante, long intermedio, long avanzado,
                                   List<String> habilidades, List<String> ejemplos) {
}
