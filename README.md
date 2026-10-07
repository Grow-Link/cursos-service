# cursos-service

Cursos, prerequisitos (con validacion de ciclos), catalogo cerrado de
habilidades, baja logica, catalogo general con filtros, completacion de
cursos, y roadmap generado con IA (con modo de respaldo mientras no haya
API key de Claude configurada).

## Como correrlo

```bash
docker compose up -d   # Postgres en localhost:5437
mvn spring-boot:run    # arranca en localhost:8086
```

Variables de entorno requeridas:

| Variable | Para que |
|---|---|
| `GROWLINK_JWT_SECRET` | Mismo secreto compartido con auth-service / usuarios-service, para validar el JWT |
| `CLAUDE_API_KEY` | Opcional. Si no esta configurada, el roadmap se genera con el modo de respaldo (ver abajo) |

`GROWLINK_JWT_SECRET` no tiene valor por defecto (a proposito, para no
dejar un secreto hardcodeado en el repo): si no esta seteada en el entorno
donde corres `mvn spring-boot:run`, el arranque falla con
`Could not resolve placeholder 'GROWLINK_JWT_SECRET'`. Hay que setearla
**antes** de correr el comando, en la misma terminal:

```powershell
# PowerShell
$env:GROWLINK_JWT_SECRET = "el-mismo-secreto-que-usa-auth-service-o-usuarios-service"
mvn spring-boot:run
```

```bash
# bash / Git Bash
GROWLINK_JWT_SECRET="el-mismo-secreto-que-usa-auth-service-o-usuarios-service" mvn spring-boot:run
```

Tiene que ser **el mismo valor** que usa `usuarios-service` (su
`JWT_SECRET`) y `auth-service` para firmar los tokens: si no coincide,
cursos-service arranca bien pero rechaza todos los tokens como invalidos
(la peticion queda como anonima y responde 403, sin ningun error visible
que lo delate). `$env:GROWLINK_JWT_SECRET` en PowerShell solo dura esa
ventana; para dejarla permanente en la maquina:

```powershell
[System.Environment]::SetEnvironmentVariable("GROWLINK_JWT_SECRET", "el-secreto", "User")
# requiere abrir una terminal nueva para que tome efecto
```

> Nota de esquema: el cambio de habilidades de texto libre a catalogo
> cerrado modifica la forma de la tabla `curso_habilidad`. Como el proyecto
> usa `ddl-auto: update` (sin Flyway/Liquibase), en una base de datos de
> desarrollo que ya tenia datos con el esquema viejo conviene borrar esa
> tabla (o la base completa) antes de levantar el servicio con este cambio.

## Seguridad

Mismo patron que usuarios-service: un `TokenAuthenticationFilter` valida el
JWT emitido por auth-service con el secreto compartido (`GROWLINK_JWT_SECRET`)
y puebla el `SecurityContext` con el userId (`sub`, numerico) y los roles.
Todo `/api/**` requiere autenticacion.

- Crear, editar y dar de baja un curso: solo el publicador dueño del curso o
  un usuario con rol `ADMIN` (cualquier curso).
- Completar un curso y generar un roadmap: cualquier usuario autenticado.
- Ver cursos, el catalogo de habilidades y el catalogo general: cualquier
  usuario autenticado.

## Documentación interactiva (Swagger UI)

Con el servicio corriendo: **http://localhost:8086/swagger-ui.html**
(`/v3/api-docs` para el JSON crudo de OpenAPI). Los endpoints están
agrupados por `@Tag` en **Cursos**, **Habilidades** y **Roadmap**.

Para probar los endpoints protegidos, usa el botón **Authorize** (arriba a
la derecha) y pega un JWT válido (`Bearer <token>`, emitido por
auth-service/usuarios-service, firmado con el mismo `GROWLINK_JWT_SECRET`).
Los endpoints de crear/editar/dar de baja curso traen una nota en su
descripción indicando que requieren ser el publicador dueño o tener rol
`ADMIN`, para saber qué token usar al probarlos.

## Endpoints

| Metodo | Ruta | Que hace |
|---|---|---|
| POST | /api/cursos | Crea un curso (valida que las habilidades sean de su categoria) |
| GET | /api/cursos/{id} | Ver un curso, con sus prerequisitos |
| GET | /api/cursos?publicadorUsuarioId=X | "Mis cursos" del publicador (HU-07, incluye inactivos) |
| GET | /api/cursos?categoria=X&nivel=Y | Catalogo general (HU-13, solo activos, filtros opcionales) |
| GET | /api/cursos/estado-roadmap?ids=1,2,3 | HU-10: cuales de esos cursos ya no estan activos |
| PUT | /api/cursos/{id}/prerequisitos | Reemplaza los prerequisitos de un curso (valida ciclos) |
| POST | /api/cursos/sugerir-prerequisitos | Sugiere prerequisitos (cursos activos de la misma categoria) al publicar un curso, para preseleccionar editable en el formulario |
| PUT | /api/cursos/{id} | HU-08: edita titulo, descripcion, nivel, habilidades, link |
| PATCH | /api/cursos/{id}/baja | HU-09: baja logica (activo=false, no borra la fila) |
| POST | /api/cursos/{id}/completar | HU-14: marca el curso como completado por un usuario |
| GET | /api/cursos/completados?usuarioId=X | HU-15: historial de completados (incluye cursos ya inactivos) |
| GET | /api/habilidades?categoria=X | Catalogo cerrado de habilidades, filtrable por categoria |
| POST | /api/roadmap/generar | HU-11: genera y guarda un roadmap para el usuario |
| GET | /api/roadmap/mio?usuarioId=X | HU-11/HU-05: el roadmap mas reciente del usuario (404 si no tiene) |

## Catalogo cerrado de habilidades

`Habilidad` (id, nombre, categoria) reemplaza el texto libre que tenia
`Curso.habilidades`. La relacion curso-habilidad vive en la tabla
`curso_habilidad` referenciando IDs de `Habilidad`. Al crear o editar un
curso se valida que cada habilidad elegida sea de la misma categoria que el
curso (`HabilidadCategoriaInvalidaException`, 400).

El catalogo se siembra solo (`HabilidadCatalogoSeeder`, un
`CommandLineRunner` que corre si la tabla esta vacia) con la lista fija de
categorias y habilidades del business case. Es un punto de partida: se
puede ampliar agregando filas directamente, sin tocar codigo.

## Como funciona la validacion de ciclos

Antes de guardar un prerequisito nuevo, se recorre la cadena de
prerequisitos del que se quiere agregar, para ver si esa cadena ya llega
de vuelta al curso original. Si llega, es porque agregar ese prerequisito
cerraria un ciclo (A necesita B, B ya necesita A, directa o
indirectamente), y se rechaza.

## Roadmap con IA (HU-11/12)

`RoadmapAiClient` es el puerto hacia la IA. `RoadmapService` le manda el
catalogo completo de cursos activos con sus prerequisitos reales (nunca un
subconjunto recortado de antemano) y valida la respuesta antes de guardar:
todo cursoId debe existir en ese catalogo, sin repetidos, y cada curso debe
aparecer despues de los prerequisitos que tambien esten en la ruta. Si la
respuesta no cumple eso (o la llamada falla), se usa el modo de respaldo.

- `ClaudeRoadmapAiClient`: integracion real con la API de Claude (Messages
  API). Solo se activa si `CLAUDE_API_KEY` esta configurada
  (`@ConditionalOnExpression`). **No se ha probado contra la API real**: falta la
  llave. Si la respuesta trae un bloque de "thinking" antes del texto se lee
  igual (solo se juntan los bloques de tipo `text`), y eso esta cubierto por
  pruebas con respuestas con la forma real de la API.
- `FallbackTopologicoRoadmapAiClient`: modo de respaldo, siempre disponible.
  Arma el subgrafo de cursos cuya categoria esta en los intereses del
  usuario y cuyo nivel no supera el pedido, y lo ordena con Kahn
  (ordenacion topologica) usando los prerequisitos reales entre esos
  cursos. Es el que esta activo mientras no haya API key.

Conectar la IA real es, literalmente, configurar `CLAUDE_API_KEY`:
`RoadmapService` detecta el bean de `ClaudeRoadmapAiClient` automaticamente
(via `ObjectProvider`) y lo prefiere sobre el modo de respaldo, sin tocar codigo.

**Que no se recomiende un curso dado de baja.** Se cumple en capas, y no depende
de lo que conteste la IA:

1. La IA solo recibe el catalogo de cursos **activos**, nunca ve uno dado de baja.
2. Aunque aun asi recomendara uno dado de baja (o uno inventado), la respuesta se
   rechaza y se usa el respaldo, que tambien trabaja solo con cursos activos.
3. Un roadmap que ya estaba guardado no se reescribe solo: `GET
   /api/cursos/estado-roadmap` marca los cursos que dejaron de estar activos
   (`requiereRegenerar`) para que el usuario lo regenere.

Esto esta probado en `RoadmapRestriccionTest` con una IA simulada que recomienda
cursos dados de baja, cursos inventados, o falla.

**Quien lo genero.** El roadmap guarda `generadoPor`: `IA` solo si Claude contesto
y la respuesta paso la validacion, y `RESPALDO` en cualquier otro caso (sin llave,
falla, o respuesta invalida). El frontend lo muestra para no decir "generado por
IA" cuando fue el respaldo. Ojo: el respaldo **no usa las metas** del usuario,
solo sus intereses y su nivel, las metas solo las lee la IA.

**Solo con tu propio usuario.** `POST /api/roadmap/generar`, `GET
/api/roadmap/mio`, `POST /api/cursos/{id}/completar` y `GET
/api/cursos/completados` reciben un `usuarioId`, pero ese dato lo escribe el
cliente, asi que se compara con el del token y si no coincide responde 403. Un
ADMIN si puede actuar por otros.

`GET /api/roadmap/mio` devuelve los cursos del roadmap guardado con sus
prerequisitos reales; con eso el frontend arma el grafo visual (HU-12, ver
"Roadmap como grafo" en el README de GrowLink-FRONTEND).

`POST /api/cursos/sugerir-prerequisitos` (body: `categoria`, `nivel`,
`titulo`, `descripcion?`) reutiliza esta misma infraestructura (mismo
`RoadmapAiClient`/fallback, sin duplicar logica de cliente IA) para
preseleccionar prerequisitos editables en el formulario de publicar curso.
Los candidatos son siempre cursos activos de la misma categoria. El modo de
respaldo sugiere los de menor nivel dentro de esa categoria. La respuesta
incluye `modoRespaldo` para que el frontend pueda indicarlo si quiere.

## Pruebas

```bash
mvn test
```

`CursoControllerTest` cubre la creacion de cursos y la validacion de ciclos
(ya existia). `CursoFeaturesTest` cubre lo nuevo: catalogo de habilidades y
su validacion por categoria, HU-08 (editar, con chequeo de dueño/admin),
HU-09 (baja logica y su efecto en el catalogo vs. "mis cursos"), HU-10
(estado-roadmap), HU-13 (catalogo con filtros), HU-14/15 (completar cursos,
incluyendo que el historial sobrevive a la baja del curso) y HU-11/12
(roadmap en modo de respaldo, validando que respeta el orden real de
prerequisitos).

## Despliegue

Se despliega en Azure App Service, el flujo de ramas y ambientes esta
explicado en el README del repo `infra`. `ci.yml` corre las pruebas en cada
push a `main`, `avance` o `final`, y `cd.yml` despliega la rama a su ambiente
de GitHub (`main` -> `actual`, `avance` -> `avance`, `final` -> `final`).
Tambien hay un `Dockerfile`.

El servicio no guarda nada en memoria (la sesion es un JWT, lo demas esta en la
base), asi que se puede correr con varias instancias detras de un balanceador.
Cada respuesta trae el header `X-Instancia` con la instancia que la atendio. Una
cosa: el catalogo de cursos y de habilidades de prueba se crea al arrancar si la
base esta vacia, por eso la primera vez hay que dejar una sola instancia hasta
que arranque (si no, se duplican). El script `levantar.sh` del repo `infra` ya
lo hace asi.

| Variable | Para que sirve |
|---|---|
| `PORT` y `WEBSITES_PORT` | Poner las dos con `8086` |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | La base Postgres |
| `GROWLINK_JWT_SECRET` | Obligatoria, el mismo secreto que usa usuarios-service |
| `CLAUDE_API_KEY` | La llave de la API de Claude, con ella el roadmap se genera con IA real |
| `ROADMAP_AI_MODEL` | Opcional, el modelo de Claude a usar (por defecto `claude-sonnet-5-5`) |

## Pendiente

- La integracion real con la API de Claude (`ClaudeRoadmapAiClient`) esta
  construida pero sin probar contra la API real. El grupo ya acordo pagar
  la API, asi que solo falta poner `CLAUDE_API_KEY` en el ambiente y
  probarla. Mientras no haya llave, el roadmap se genera con el modo de
  respaldo (ordenacion topologica), que es completamente funcional.
- No hay Flyway/Liquibase: el esquema se maneja con `ddl-auto: update`,
  igual que antes de este cambio. Ver la nota de esquema arriba sobre
  `curso_habilidad`.
