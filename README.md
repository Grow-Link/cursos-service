# cursos-service

Cursos y sus prerequisitos. Por ahora tiene crear curso, listar los de un
publicador, y actualizar prerequisitos con la validacion de ciclos
(HU-06 en parte, HU-07). Todavia no tiene la sugerencia de prerequisitos
con IA (necesita la API de Claude, que sigue pendiente de resolver con el
grupo), ni el resto de HU-08 (editar titulo, descripcion, etc), ni nada
de roadmap (HU-11 a HU-13).

## Como correrlo

```bash
docker compose up -d   # Postgres en localhost:5437
mvn spring-boot:run    # arranca en localhost:8086
```

## Endpoints

| Metodo | Ruta | Que hace |
|---|---|---|
| POST | /api/cursos | Crea un curso, con sus prerequisitos si los manda |
| GET | /api/cursos/{id} | Ver un curso, con sus prerequisitos |
| GET | /api/cursos?publicadorUsuarioId=X | Los cursos de un publicador (HU-07) |
| PUT | /api/cursos/{id}/prerequisitos | Reemplaza los prerequisitos de un curso |

## Como funciona la validacion de ciclos

Antes de guardar un prerequisito nuevo, se recorre la cadena de
prerequisitos del que se quiere agregar, para ver si esa cadena ya llega
de vuelta al curso original. Si llega, es porque agregar ese prerequisito
cerraria un ciclo (A necesita B, B ya necesita A, directa o
indirectamente), y se rechaza.

Escribiendo la prueba de esto encontre un problema de aislamiento entre
pruebas, no del codigo: las 3 pruebas comparten la misma base H2 porque no
se limpia entre una y otra, asi que si dos pruebas usan el mismo
publicadorUsuarioId se contaminan los conteos. Ya esta corregido usando un
id distinto por prueba.

## Pendiente

- La sugerencia de prerequisitos con IA (parte de HU-06) - depende de que
  el grupo resuelva el tema del pago de la API de Claude.
- El resto de HU-08 (editar titulo, descripcion, nivel, habilidades, link).
- HU-09 (dar de baja) y HU-10 (aviso de roadmap desactualizado).
- HU-11 a HU-13 (roadmap con IA, visualizarlo, catalogo con filtros).
- Las habilidades hoy son texto libre, no el catalogo cerrado por
  categoria que describe el business case.

## Pruebas

```bash
mvn test
```
