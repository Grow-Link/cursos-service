# cursos-service

Cursos, prerequisitos (con validacion de ciclos), catalogo cerrado de
habilidades, baja logica, catalogo general con filtros, completacion de
cursos, y roadmap generado con IA (con modo de respaldo mientras no haya
API key de Claude configurada).

## Como correrlo

```bash
docker compose up -d   # Postgres en localhost:5437
SPRING_DATASOURCE_PASSWORD=growlink mvn spring-boot:run   # arranca en localhost:8086
```

La contrasena de la base ya no esta escrita en el codigo (SonarCloud la marcaba como secreto expuesto): se pasa con la variable
`SPRING_DATASOURCE_PASSWORD`. La del Postgres de este `docker-compose.yml` es `growlink`.

Los logs salen en JSON, una linea por evento (ver [Logs en JSON](#logs-en-json)). Para leerlos a ojo en desarrollo:
`SPRING_PROFILES_ACTIVE=logs-texto mvn spring-boot:run`.

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

### Proveedor de identidad: `AUTH_MODE`

El interruptor `AUTH_MODE` (ver `SecurityConfig`) elige de donde viene la identidad de quien llama. Las dos
cadenas protegen exactamente lo mismo y dejan el mismo usuario autenticado (`usuarioId` + roles), asi que los
controllers y las reglas de roles no cambian:

- `legacy` (valor por defecto): el JWT compartido con auth-service / usuarios-service (`GROWLINK_JWT_SECRET`),
  tal como funciona hoy. Mientras no se cambie a mano, nada de lo actual cambia.
- `oidc`: el token lo emite y firma el proveedor de identidad (Microsoft Entra ID) y este servicio solo lo valida
  como resource server: firma con las llaves publicas del emisor, emisor (`AUTH_ISSUER_URI`), audiencia
  (`AUTH_AUDIENCE`) y vigencia. Si el token falta, vencio, viene de otro emisor, es para otra aplicacion o esta
  mal firmado, responde `401` y lo deja en el log (`RegistroDeRechazosOidc`). En este modo el servicio ya no
  necesita `GROWLINK_JWT_SECRET`.

El id numerico de usuario de GrowLink se busca en los claims de `AUTH_CLAIMS_USUARIO` (por defecto
`growlink_uid,uid,sub`), porque el `sub` de Entra ID es un texto opaco y no un numero; los roles salen de
`AUTH_CLAIMS_ROLES` (por defecto `roles,rol,groups`). Ver `ConvertidorDeTokenOidc`.

#### Multi-inquilino: `AUTH_MULTI_TENANT`

La app se registra en Entra como **multi-inquilino** (cuentas de cualquier directorio organizacional y cuentas
personales de Microsoft). Con ese registro el `iss` del token **no es uno fijo**: trae el inquilino de quien
inicia sesion. Comparar el `iss` contra un solo emisor rechazaria a casi todo el mundo, y aceptar cualquier `iss`
seria regalar el servicio (bastaria con que alguien levantara su propio emisor). El punto medio es
`AUTH_MULTI_TENANT`:

- `false` (valor por defecto): un solo emisor, el de `AUTH_ISSUER_URI`. Es el comportamiento de siempre y no
  cambia en nada.
- `true`: se acepta cualquier emisor que encaje **entero** en
  `https://login.microsoftonline.com/<guid-del-inquilino>/v2.0` y, ademas, se exige que el claim `tid` sea ese
  **mismo** GUID. Ver `ValidadorDeEmisorMultiTenant`.

Lo que eso rechaza, y que es justo el ataque que importa cuando se abre el conjunto de emisores:

| `iss` que llega | Por que no entra |
|---|---|
| `http://login.microsoftonline.com/<guid>/v2.0` | No es `https` |
| `https://login.microsoftonline.com.evil.com/<guid>/v2.0` | Otro host que solo *empieza* igual (lo registra cualquiera) |
| `https://login.microsoftonline.com/<guid>` | Sin `/v2.0` |
| `https://evil.com/?x=https://login.microsoftonline.com/<guid>/v2.0` | El emisor bueno metido dentro de otra cosa: el patron esta anclado |
| `https://login.microsoftonline.com/common/v2.0` | `/common` y `/organizations` son por donde se *pide* el token, nunca el emisor de uno ya emitido |
| `iss` del inquilino A con `tid` del B | El token dice ser de un inquilino distinto al de su emisor |

Las cuentas personales de Microsoft no necesitan nada aparte: llegan con el inquilino
`9188040d-6c67-4c5b-b112-36a304b66dad`, que encaja en el patron como cualquier otro.

`AUTH_ALLOWED_TENANTS` (opcional, solo en multi-inquilino) acota la cosa a una lista de GUIDs de inquilino
separados por comas: sirve para una prueba piloto o para un despliegue limitado a unas organizaciones. Vacia
significa "cualquier inquilino de Entra", que es lo que quiere una app multi-inquilino de verdad. Un GUID mal
escrito en esa lista **no deja arrancar** el servicio, para que no se quede gente fuera en silencio.

En multi-inquilino las llaves publicas **no** se pueden descubrir a partir del emisor (hay uno por inquilino),
asi que `AUTH_JWKS_URI` toma por defecto el juego comun de Entra,
`https://login.microsoftonline.com/common/discovery/v2.0/keys`. Sigue siendo configurable.

`AUTH_AUDIENCE` acepta **varias audiencias separadas por comas** y el token pasa si su `aud` coincide con
alguna: Entra pone `api://<client-id>` o el `<client-id>` pelado segun como este configurada la aplicacion y
segun quien pida el token, asi que lo normal es poner las dos formas. Con el emisor ya no siendo uno solo, esta
es la comprobacion que de verdad delimita "para quien es este token".

Lo demas no cambia al pasar a multi-inquilino: solo RS256, `exp` y `sub` obligatorios, la audiencia verificada,
el interruptor `AUTH_MODE` (con `legacy` por defecto) y el fail-fast si la configuracion no tiene sentido.

#### De donde sale la identidad: `auth.identity-source`

El token de Entra **no trae la identidad de GrowLink**. Su `sub` es un texto opaco (algo como
`AAAAAAAAAAAAAAAAAAAAAIk_bMBSKsTxQ4Y-eV3VvQ4`), no el `usuarioId` numerico con el que estan hechas las tablas de
este servicio (`publicadorUsuarioId`, `roadmap.usuario_id`, `curso_completado.usuario_id`...), y el rol de
GrowLink es cosa nuestra, no de Microsoft. Quien sabe las dos cosas es **usuarios-service**.

- `usuarios-service` (valor por defecto): despues de validar el token (firma, emisor, audiencia, `exp`...), este
  servicio llama a `GET {USUARIOS_SERVICE_URL}/api/usuarios/me` **reenviando el mismo token tal cual** y se queda
  con el `usuarioId` y el `rol` de la respuesta. Ver `ClienteDeUsuariosService`.
- `claims`: el `usuarioId` y el rol se leen del propio token (`AUTH_CLAIMS_USUARIO` / `AUTH_CLAIMS_ROLES`). Se
  conserva para desarrollo y pruebas sin usuarios-service levantado. **En produccion no se quiere**, porque ahi
  el rol lo diria el propio token.

Cualquier otro valor **no deja arrancar** el servicio, igual que `USUARIOS_SERVICE_URL` ausente con
`identity-source=usuarios-service`. En modo `legacy` nada de esto se usa: el `usuarioId` y el rol vienen en el JWT
compartido, como siempre.

**Cache.** Consultar a usuarios-service en *cada* peticion le doblaria el trafico y sumaria su latencia a la
nuestra, asi que la identidad se guarda `AUTH_IDENTITY_CACHE_SECONDS` (60 s por defecto), con la clave puesta al
SHA-256 del token (no el token: es una credencial). Dos topes a proposito:

- la vigencia es el **retraso maximo** con el que este servicio se entera de un cambio de rol en
  usuarios-service, y por eso es corta: un rol es un permiso;
- una entrada **nunca vive mas que el `exp` de su token**, para que la cache no sea una forma de alargarlo.

Hay tope de entradas (`AUTH_IDENTITY_CACHE_MAX`, 10 000) para que una lluvia de tokens distintos no haga crecer la
memoria. De paso, la cache tapa un hueco de disponibilidad: si usuarios-service se cae, quien ya tiene su
identidad guardada **sigue trabajando** hasta que se le venza.

**Que pasa cuando usuarios-service no colabora** (tiempos limite cortos a proposito: 2 s de conexion, 3 s de
lectura, porque esta llamada esta en el camino de cada peticion):

| usuarios-service | Respuesta de este servicio | Por que |
|---|---|---|
| `200` con `usuarioId` | la peticion sigue | esa es la identidad, y se guarda un rato |
| `401` | `401` | no reconoce el token; no hay nada que hacer |
| otro `4xx` (`403`, `404`...) | `401` | el token es valido pero no se resuelve a un usuario de GrowLink. Reintentar no lo arregla, asi que no es un `503` |
| `5xx`, timeout, no abre | `503` + `Retry-After` | el token puede estar perfecto y el problema ser nuestro: un `401` mandaria a la gente a iniciar sesion otra vez para nada |
| `200` sin `usuarioId` | `503` | el contrato no cuadra |

En los cuatro rechazos queda el motivo en el log con el `X-Request-Id` de la peticion (y ese mismo
`X-Request-Id` se le reenvia a usuarios-service, para poder seguir una peticion de un servicio al otro). Lo que
**nunca** pasa es aceptar el token a medias ni caer a leer los claims: eso seria dejar que el propio token diga
su rol, que es justo lo que este modo evita.

#### Quien puede publicar cursos

Crear un curso (`POST /api/cursos`) pide rol **PUBLICADOR o ADMIN**; un `USUARIO` recibe `403` y no se crea nada
(ver `AuthenticatedUser.exigirPuedePublicar`). La regla es una sola y vale en los dos modos: lo que cambia es de
donde sale el rol — del JWT compartido en `legacy`, y de usuarios-service en `oidc`.

#### Ejemplo de configuracion para Entra (multi-inquilino)

En el registro de la aplicacion en Entra: *Supported account types* = **Accounts in any organizational directory
and personal Microsoft accounts**, y en *Expose an API* un App ID URI `api://<client-id>` con su scope.

```bash
AUTH_MODE=oidc
AUTH_MULTI_TENANT=true
# las dos formas que Entra puede poner en `aud`, por si acaso
AUTH_AUDIENCE=api://11112222-3333-4444-5555-666677778888,11112222-3333-4444-5555-666677778888
# opcional: solo estas organizaciones. Vacia o sin poner = cualquier inquilino de Entra
AUTH_ALLOWED_TENANTS=
# opcional: por defecto ya es el juego comun de Entra en multi-inquilino
# AUTH_JWKS_URI=https://login.microsoftonline.com/common/discovery/v2.0/keys
# en modo oidc NO hace falta AUTH_ISSUER_URI (no hay un emisor fijo) ni GROWLINK_JWT_SECRET

# la identidad de GrowLink (usuarioId numerico + rol) la resuelve usuarios-service: el token de Entra no la trae
USUARIOS_SERVICE_URL=http://usuarios-service:8081
# opcional: cuanto se guarda una identidad ya resuelta (es el retraso con el que se nota un cambio de rol)
AUTH_IDENTITY_CACHE_SECONDS=60
```

Y la misma app en **un solo inquilino** (lo de antes, por si se quiere volver):

```bash
AUTH_MODE=oidc
AUTH_MULTI_TENANT=false
AUTH_ISSUER_URI=https://login.microsoftonline.com/<guid-del-inquilino>/v2.0
AUTH_AUDIENCE=api://11112222-3333-4444-5555-666677778888
```

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
| PUT | /api/cursos/{id} | HU-08: edita titulo, descripcion, nivel, habilidades, link, horas, temario y (opcional) examen |
| PATCH | /api/cursos/{id}/baja | HU-09: baja logica (activo=false, no borra la fila) |
| GET | /api/cursos/resumen | Las areas que tienen cursos activos AHORA (cuantos, horas, niveles, habilidades, ejemplos): es lo que se muestra antes de pedir la meta |
| GET | /api/cursos/{id}/examen | Las preguntas y opciones del examen (nunca la respuesta correcta) |
| POST | /api/cursos/{id}/examen | HU-14: presenta el examen; con 70 % o mas el curso queda completado. El servidor califica |
| POST | /api/cursos/{id}/completar | Solo ADMIN (para sembrar datos): completar sin examen |
| GET | /api/cursos/completados?usuarioId=X | HU-15: historial de completados (incluye cursos ya inactivos) |
| GET | /api/habilidades?categoria=X | Catalogo cerrado de habilidades, filtrable por categoria |
| POST | /api/roadmap/generar | HU-11: genera y guarda un roadmap para el usuario |
| GET | /api/roadmap/mio?usuarioId=X | HU-11/HU-05: el roadmap mas reciente del usuario (404 si no tiene) |
| GET | /api/roadmap/completados?usuarioId=X | CONTRATO con usuarios-service: roadmaps que el usuario ya completo del todo, uno por semilla de jardin (lista vacia si no tiene ninguno) |

## Catalogo, examenes y control de metas

- Un curso lleva descripcion, **horas**, **temario**, enlace, habilidades, prerequisitos y un **examen** de 3 a 15 preguntas de 4 opciones (el publicador lo escribe al publicar). Sin examen no se puede completar.
- **Completar un curso exige aprobar su examen** (minimo 70 %), calificado en el servidor: el cliente nunca recibe cual era la respuesta correcta.
- **Mandar el mismo examen aprobado dos veces a la vez** (doble clic, dos pestanas, un reintento) lo deja completado una sola vez y los dos envios reciben su resultado. Antes, el que perdia la carrera respondia 500: chocaba con la restriccion unica dentro de una transaccion que ya no se podia confirmar. Ahora `ExamenService.presentar` no abre una transaccion propia y el guardado va solo (ver el comentario ahi y `ExamenDobleEnvioTest`, con la carrera armada a proposito y con 8 hilos de verdad).
- Al arrancar se siembra un catalogo de demostracion (`src/main/resources/catalogo-demo.json`, 32 cursos reales en 5 areas) y un usuario de demostracion (id 5, Esteban) con roadmap y 3 cursos aprobados. Es seguro correrlo en cada arranque: no pisa lo que un publicador haya editado.
- **Control de metas** (`MetaValidator`): una meta sin sentido responde 400 y una meta sin cursos relacionados en las areas elegidas responde 422, con un mensaje que la persona puede leer. Nunca se devuelve una ruta inventada o por defecto.
- Las **alternativas** de un paso son cursos de la misma area que trabajan las mismas habilidades a un nivel igual o mas basico; si no hay ninguno equivalente, la lista va vacia.

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

**Que un roadmap nuevo no pueda "ganar" semillas de jardin sin trabajo real
(anti-farming, parte 1).** El jardin de usuarios-service da una semilla por
cada roadmap completado (ver `GET /api/roadmap/completados` abajo), asi que
`RoadmapService.generar` nunca ofrece un curso que esa persona ya completo:

1. Antes de armar el catalogo que se le manda a la IA o al respaldo, se
   excluyen los cursos que ya estan en `CursoCompletado` de esa persona
   (`RoadmapService.sinCompletados`). Ni la IA ni el respaldo los ven, asi
   que no pueden volver a proponerlos.
2. Un curso completado que era prerequisito de otro queda simplemente afuera
   de ese catalogo: para quien arma la ruta ya no existe, asi que se trata
   como cumplido (no bloquea al que lo necesitaba ni rompe el orden
   topologico). `FallbackTopologicoRoadmapAiClient.conPrerequisitos` ya
   ignoraba un prerequisito que no encontraba en el catalogo que se le pasa,
   asi que esto no necesito cambiar ese algoritmo.
3. Si aun asi la IA real propone un curso ya completado, la validacion de la
   respuesta lo rechaza (ya no existe en el catalogo disponible que se usa
   para validar) y se cae al modo de respaldo, igual que con un curso dado
   de baja o inventado.
4. Si tras excluir los completados no queda ningun curso en las areas
   elegidas, se responde 422 con un mensaje distinto al de "todavia no hay
   cursos de X": "Ya completaste todos los cursos de X".

Probado en `RoadmapAntiFarmingGeneracionTest` (contra el algoritmo de
respaldo real) y en `RoadmapAntiFarmingTest` (con una IA simulada que insiste
en proponer un curso ya completado).

**Quien lo genero.** El roadmap guarda `generadoPor`: `IA` solo si Claude contesto
y la respuesta paso la validacion, y `RESPALDO` en cualquier otro caso (sin llave,
falla, o respuesta invalida). El frontend lo muestra para no decir "generado por
IA" cuando fue el respaldo. Ojo: el respaldo **no usa las metas** del usuario,
solo sus intereses y su nivel, las metas solo las lee la IA.

**Solo con tu propio usuario.** `POST /api/roadmap/generar`, `GET
/api/roadmap/mio`, `GET /api/roadmap/completados`, `POST
/api/cursos/{id}/completar` y `GET /api/cursos/completados` reciben un
`usuarioId`, pero ese dato lo escribe el cliente, asi que se compara con el
del token y si no coincide responde 403. Un ADMIN si puede actuar por otros.

`GET /api/roadmap/mio` devuelve los cursos del roadmap guardado con sus
prerequisitos reales; con eso el frontend arma el grafo visual (HU-12, ver
"Roadmap como grafo" en el README de GrowLink-FRONTEND).

**`GET /api/roadmap/completados`** (CONTRATO con usuarios-service, no cambiar
su forma sin avisar antes): da una semilla de jardin por cada roadmap que esa
persona ya completo del todo. Generar un roadmap nuevo **nunca borra ni
reemplaza** los anteriores (cada `POST /api/roadmap/generar` guarda una fila
nueva en `roadmap`; "mio" solo muestra la mas reciente, ver `Roadmap`), asi
que un roadmap completado no desaparece aunque la persona genere otro
despues. La respuesta, por roadmap completado:

```json
{ "roadmapId": 5, "categoria": "INGENIERIA_SISTEMAS", "titulo": "...",
  "totalCursos": 6, "horasTotales": 54, "completadoEn": "2026-10-09T12:00:00Z" }
```

- `categoria` es la que mas cursos tiene en ese roadmap; en empate, la del
  curso que aparece primero por `orden`.
- `titulo` sale de las metas del roadmap, recortado a 80 caracteres sin
  partir una palabra a la mitad.
- `horasTotales` suma `duracionHoras` de sus cursos.
- `completadoEn` es la fecha del ultimo curso completado.

Un roadmap cuenta como completado cuando tiene al menos un curso y, ademas
de las dos reglas de anti-farming de abajo, TODOS sus cursos estan en
`CursoCompletado` de esa persona (un curso dado de baja despues de
completarlo sigue contando: la baja es logica y no toca `CursoCompletado`).
Todo con consultas en bloque (`IN`), nunca una consulta por curso o por
roadmap (ver `RoadmapService.completados`).

**Anti-farming, parte 2: que cada curso completado pague como maximo una
semilla.** La parte 1 (arriba) evita que un roadmap NUEVO vuelva a incluir
un curso ya completado, pero no alcanza sola: un roadmap viejo (de antes de
este arreglo, o completado por coincidencia con cursos que ya traia de
antes) podria contar sin trabajo real, y dos roadmaps distintos con los
mismos cursos pendientes pagarian doble la primera vez que se completen
juntos. Por eso, ademas de lo anterior:

1. Un roadmap solo califica si TODOS sus cursos se completaron **despues**
   de que el roadmap se creo (`CursoCompletado.fecha` posterior a
   `Roadmap.creadoEn`). Un curso completado antes (en otro roadmap previo, o
   antes de que existiera la parte 1) no cuenta como trabajo hecho para
   este roadmap, y esto protege tambien a los roadmaps guardados antes de
   este arreglo.
2. Cada curso completado alimenta como maximo UN roadmap. Los roadmaps que
   pasan el punto 1 se procesan en el orden real en que se completaron
   (`completadoEn` ascendente); el primero en llegar a un curso se lo queda,
   y si otro roadmap posterior comparte alguno de esos cursos ya no
   califica.

Las dos reglas corren sobre los datos que ya se trajeron en bloque (sin
consultas nuevas): son deterministas y baratas. Probado en
`RoadmapAntiFarmingTest`.

`POST /api/cursos/sugerir-prerequisitos` (body: `categoria`, `nivel`,
`titulo`, `descripcion?`) reutiliza esta misma infraestructura (mismo
`RoadmapAiClient`/fallback, sin duplicar logica de cliente IA) para
preseleccionar prerequisitos editables en el formulario de publicar curso.
Los candidatos son siempre cursos activos de la misma categoria. El modo de
respaldo sugiere los de menor nivel dentro de esa categoria. La respuesta
incluye `modoRespaldo` para que el frontend pueda indicarlo si quiere.

## Pruebas

```bash
mvn test     # solo las pruebas
mvn verify   # las pruebas y la cobertura: target/site/jacoco/index.html
```

### Cobertura y SonarCloud en el CI

`ci.yml` corre `mvn -B verify`, que con JaCoCo deja la cobertura en `target/site/jacoco/` (se puede bajar como
artefacto `cobertura-cursos-service` desde el resumen de la corrida). Despues manda el analisis a SonarCloud, que lee
ese mismo reporte (`jacoco.xml`) sin volver a correr las pruebas. El build no se cae por un porcentaje de cobertura:
eso lo decide la puerta de calidad de SonarCloud.

Mientras el repo no tenga el secreto `SONAR_TOKEN`, el paso de SonarCloud se **omite** con un aviso y el workflow queda
en verde. Para prenderlo:

1. En SonarCloud, importar el repo desde la organizacion de GitHub y, en *Administration > Analysis Method*, apagar
   *Automatic Analysis* (si queda prendido, el analisis desde el CI falla).
2. Crear un token en SonarCloud (*My Account > Security*) y guardarlo en GitHub como secreto del repo `SONAR_TOKEN`.
3. El CI usa la organizacion `grow-link` y la clave `Grow-Link_cursos-service`, que son las que pone SonarCloud al
   importar. Si quedaron distintas, poner las variables del repo `SONAR_ORGANIZATION` y `SONAR_PROJECT_KEY`.

`CursoControllerTest` cubre la creacion de cursos y la validacion de ciclos
(ya existia). `CursoFeaturesTest` cubre lo nuevo: catalogo de habilidades y
su validacion por categoria, HU-08 (editar, con chequeo de dueño/admin),
HU-09 (baja logica y su efecto en el catalogo vs. "mis cursos"), HU-10
(estado-roadmap), HU-13 (catalogo con filtros), HU-14/15 (completar cursos,
incluyendo que el historial sobrevive a la baja del curso) y HU-11/12
(roadmap en modo de respaldo, validando que respeta el orden real de
prerequisitos).

`RoadmapCompletadosTest` cubre `GET /api/roadmap/completados` (con una IA
falsa para controlar exactamente que cursos entran a cada roadmap, igual que
`RoadmapRestriccionTest`): roadmap completo, incompleto, sin cursos, de otra
persona (403), un curso dado de baja que sigue contando, la categoria por
mayoria y por empate (gana el primero por orden), varios roadmaps del mismo
usuario (solo cuentan los completos, y generar uno nuevo no borra el
anterior) y el recorte del titulo a 80 caracteres sin partir una palabra.

`RoadmapAntiFarmingGeneracionTest` cubre la parte 1 del anti-farming contra
el algoritmo de **respaldo real** (sin IA falsa): un roadmap nuevo no vuelve
a ofrecer un curso ya completado, un prerequisito ya completado no bloquea
al curso que lo necesitaba, y si ya se completo todo lo de las areas
elegidas se responde 422 con un mensaje propio (distinto del de "no hay
cursos todavia"). `RoadmapAntiFarmingTest` cubre, con la misma IA falsa que
`RoadmapRestriccionTest`/`RoadmapCompletadosTest`: una propuesta de la IA
con un curso ya completado cae al respaldo; un roadmap "viejo" con un curso
completado antes de crearse no cuenta (se simula insertandolo directo, para
cubrir tambien los roadmaps guardados antes de este arreglo); dos roadmaps
con los mismos cursos pendientes, completados juntos, solo dejan contar a
uno (determinista: gana el que se creo primero); el caso normal (completado
despues de crearse) sigue devolviendo el formato esperado; y un usuario no
ve los roadmaps completados de otro.

## Despliegue

Se despliega en Azure App Service, el flujo de ramas y ambientes esta
explicado en el README del repo `infra`. `ci.yml` corre las pruebas, mide la
cobertura y (si esta configurado) manda el analisis a SonarCloud en cada push a
`main`, `avance` o `final` y en cada pull request a `main`; `cd.yml` despliega la rama a su ambiente
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
| `ROADMAP_LIMITE_GENERACIONES` | Opcional, cuantos roadmaps puede pedir una persona en la ventana (por defecto `10`; `0` lo apaga) |
| `ROADMAP_LIMITE_VENTANA_MINUTOS` | Opcional, el largo de esa ventana en minutos (por defecto `10`) |
| `ROADMAP_IA_CORTACIRCUITO_FALLOS` | Opcional, fallos seguidos de la IA que abren el cortacircuito (por defecto `3`) |
| `ROADMAP_IA_CORTACIRCUITO_ESPERA_SEGUNDOS` | Opcional, cuanto se deja de llamar a la IA al abrirse (por defecto `60`) |
| `ROADMAP_IA_CORTACIRCUITO_DEMORA_SEGUNDOS` | Opcional, a partir de cuanto una respuesta de la IA cuenta como fallo (por defecto `10`) |
| `LIMITE_IP_MAXIMO_POR_MINUTO` | Opcional, peticiones por IP y por ventana antes del `429` (por defecto `300`; `0` lo apaga) |
| `LIMITE_IP_VENTANA_SEGUNDOS` | Opcional, el largo de esa ventana en segundos (por defecto `60`) |
| `AUTH_MODE` | Opcional, `legacy` (por defecto, el JWT compartido) u `oidc` (token del proveedor de identidad) |
| `AUTH_MULTI_TENANT` | Opcional con `AUTH_MODE=oidc`: `false` (por defecto) un solo emisor; `true` cualquier inquilino de Entra, validando el patron del `iss` y el claim `tid` |
| `AUTH_ISSUER_URI` | Obligatoria con `AUTH_MODE=oidc` **y** `AUTH_MULTI_TENANT=false`: quien firma los tokens, p.ej. `https://login.microsoftonline.com/<tenant>/v2.0`. En multi-inquilino no se usa |
| `AUTH_AUDIENCE` | Obligatoria con `AUTH_MODE=oidc`: para que aplicacion tienen que venir. Acepta **varias separadas por comas** (`api://<client-id>` y el `<client-id>` pelado), y pasa si el `aud` coincide con alguna |
| `AUTH_ALLOWED_TENANTS` | Opcional, solo en multi-inquilino: GUIDs de inquilino separados por comas. Vacia = cualquier inquilino de Entra. Un GUID mal escrito no deja arrancar |
| `AUTH_IDENTITY_SOURCE` | Opcional con `AUTH_MODE=oidc`: `usuarios-service` (por defecto) resuelve el usuarioId y el rol en usuarios-service; `claims` los lee del token (solo desarrollo). Otro valor no deja arrancar |
| `USUARIOS_SERVICE_URL` | Obligatoria con `AUTH_IDENTITY_SOURCE=usuarios-service`: la base de usuarios-service, p.ej. `http://usuarios-service:8081`. Si falta, el servicio no arranca |
| `AUTH_IDENTITY_CACHE_SECONDS` | Opcional, cuanto se guarda una identidad ya resuelta (por defecto `60`). Es el retraso maximo con el que se nota un cambio de rol; nunca pasa del `exp` del token |
| `AUTH_IDENTITY_CACHE_MAX` | Opcional, tope de identidades guardadas a la vez (por defecto `10000`) |
| `AUTH_JWKS_URI` | Opcional con `AUTH_MODE=oidc`: donde estan las llaves publicas. En multi-inquilino, por defecto las de `/common`; con un solo inquilino, se descubren desde el emisor. (`AUTH_JWK_SET_URI` se sigue aceptando como nombre viejo) |
| `SPRING_PROFILES_ACTIVE` | Opcional. En Azure no se pone (los logs salen en JSON); `logs-texto` es solo para leerlos a ojo en desarrollo |
| `METRICAS_TOKEN` | El token con el que Prometheus lee `/actuator/prometheus` (el mismo en los tres servicios, minimo 16 caracteres). Sin el, esas metricas quedan cerradas |

### Salud, trazabilidad y limite de uso

- **Salud**: `GET /actuator/health` (y `/actuator/health/liveness` y `/readiness`) responde sin credenciales y sin
  detalles. `readiness` incluye la base de datos (una replica sin base responde 503; `liveness` no la mira, a proposito,
  porque reiniciarla no arregla la base; ver `ReadinessConLaBaseDeDatosTest`): es la que conviene usar como "health check" en Azure o en el
  balanceador. Aparte de la salud, solo se exponen las metricas: `/actuator/metrics` para un ADMIN y
  `/actuator/prometheus` con el token del scrape (ver abajo).
- **Trazabilidad**: cada peticion lleva `X-Request-Id` (se respeta uno valido que mande el balanceador, si no se crea),
  vuelve en la respuesta y sale en cada linea del log, en el campo `rid` (o como `[rid=...]` con el perfil
  `logs-texto`). La llamada a usuarios-service para resolver la identidad (modo `oidc`) lo reenvia, asi una peticion
  se sigue por los dos servicios (`IdentidadDesdeUsuariosServiceTest`).
- **Limite de roadmaps**: pedir mas de `ROADMAP_LIMITE_GENERACIONES` en `ROADMAP_LIMITE_VENTANA_MINUTOS` responde
  `429` con `Retry-After` (segundos) y un mensaje que dice cuanto esperar. Cada replica cuenta por su lado.
- **Limite por IP** (`LimitePorIpFilter`): mas de `LIMITE_IP_MAXIMO_POR_MINUTO` peticiones de la misma IP dentro de
  la ventana responden `429` con `Retry-After`, en cualquier endpoint y antes de la seguridad y de la base de datos.
  La IP se toma del primer valor de `X-Forwarded-For` (delante hay un balanceador) y si no llega, de la del socket.
  Cada replica cuenta por su lado, asi que con N replicas el tope real es de hasta N veces el configurado.
  A esa IP se le quita el puerto: Azure App Service la manda como `IP:puerto` (`203.0.113.7:51234`) y el puerto
  cambia con cada conexion, asi que contada con puerto cada conexion era una IP nueva y el tope no frenaba a nadie.
  Tambien se quita de una IPv6 entre corchetes (`[2001:db8::1]:51234`); una IPv6 sin corchetes se deja tal cual.
  Ojo: ese primer valor lo puede escribir el cliente (el nginx del frontend agrega al final, no reemplaza), asi que
  quien mande su propia cabecera puede esquivar el tope; esta anotado como pendiente en el ADR-0011 del repo `infra`.
- **Cortacircuito de la IA** (`CortacircuitoDeLaIa`): tras `ROADMAP_IA_CORTACIRCUITO_FALLOS` fallos seguidos de la
  IA (error, demora de mas de `ROADMAP_IA_CORTACIRCUITO_DEMORA_SEGUNDOS`, o una respuesta que no es una ruta valida
  del catalogo) se deja de llamarla durante `ROADMAP_IA_CORTACIRCUITO_ESPERA_SEGUNDOS`; al cumplirse ese tiempo
  pasa UNA peticion de prueba, que la cierra si funciona y la vuelve a abrir si falla. Mientras esta abierto el
  roadmap lo arma el algoritmo de respaldo y la respuesta lo dice en `generadoPor: "RESPALDO"`. Una falla de la IA
  nunca sale como `5xx`.
- **Metricas para Prometheus** (`GET /actuator/prometheus`): las mismas de abajo y las de la JVM y las peticiones HTTP
  (con cubetas de latencia fijas de 10 ms a 10 s), todas con la etiqueta `instancia` (`WEBSITE_INSTANCE_ID` en Azure,
  si no el nombre del host), para los tableros de Grafana del repo `infra` (ADR-0009). Solo contesta con la cabecera
  `X-Metricas-Token: <METRICAS_TOKEN>` (ni un ADMIN entra con su token); sin ella, 403 en modo legacy y 401 en oidc.
  Sin `METRICAS_TOKEN` queda cerrado para todos. Ver `TokenDeMetricas`.
- **Metricas** (`GET /actuator/metrics/<nombre>`, **solo con token de ADMIN**; cuentan por replica y desde que arranco):

  | Medida | Que responde |
  |---|---|
  | `growlink.roadmap.generaciones` (`fuente` = `IA` o `RESPALDO`) | Cuantos roadmaps armo la IA y cuantos el respaldo |
  | `growlink.roadmap.cache` (`resultado` = `acierto` o `fallo`) | Cuantas veces se evito una llamada a la IA |
  | `growlink.roadmap.ia.llamadas` (`resultado` = `respondio`, `fallo`, `no_viable`) y `growlink.roadmap.ia.duracion` | Como le fue a Claude y cuanto tardo |
  | `growlink.roadmap.ia.propuestas` (`resultado` = `aceptada`, `corregida`, `excede_horas`, `invalida`) | Cuanto de lo que propone la IA pasa la validacion del servidor |
  | `growlink.roadmap.limite.rechazos` | Cuantas peticiones freno el tope por persona |
  | `growlink.roadmap.ia.cortacircuito.estado` | Si la IA se esta usando: `0` cerrado, `1` medio abierto (peticion de prueba), `2` abierto |
  | `growlink.roadmap.ia.cortacircuito.rechazos` | Cuantas veces no se llamo a la IA por estar el cortacircuito abierto |
  | `growlink.http.limite-ip.rechazos` | Cuantas peticiones freno el tope por IP |
  | `growlink.cursos.completados` (`via` = `examen` o `admin`, `categoria` = el area del curso) | Cuantos cursos se completaron. Ver abajo que cuenta y que no |

  Ejemplo: `curl -H "Authorization: Bearer <token de admin>" "http://localhost:8086/actuator/metrics/growlink.roadmap.generaciones?tag=fuente:RESPALDO"`.

### Cursos completados

`growlink.cursos.completados` es para el tablero de negocio (ver `MetricasDeCursos`). Para que el numero diga lo que
parece que dice:

- **`via=examen`** es una persona que aprobo el examen (la forma normal); **`via=admin`** es un ADMIN que lo marco
  con `POST /api/cursos/{id}/completar`. `categoria` es el area del curso.
- Solo cuenta un registro **nuevo**: presentar otra vez un examen ya aprobado, reprobarlo, o completar dos veces el
  mismo curso no suma.
- Solo cuenta lo que **quedo guardado**: se suma cuando la transaccion se confirma, no antes.
- Lo que siembra el arranque (los 3 cursos aprobados del usuario de demostracion) **no cuenta**: no es alguien
  completando un curso.
- Todas las combinaciones existen desde el arranque en `0`, para que el tablero no muestre "sin datos".

Ejemplo: `curl -H "Authorization: Bearer <token de admin>" "http://localhost:8086/actuator/metrics/growlink.cursos.completados?tag=via:examen"`.

## Logs en JSON

Cada linea de la salida es un objeto JSON (ver `logback-spring.xml`), para que el almacen de logs (Azure Log Analytics)
los filtre por campo:

| Campo | Que trae |
|---|---|
| `@timestamp` | Cuando paso, en UTC |
| `level`, `logger_name`, `thread_name`, `message` | Lo de siempre |
| `rid` | El `X-Request-Id` de la peticion. Falta en lo que no viene de una peticion (el arranque, por ejemplo) |
| `servicio` | `cursos-service` |
| `instancia` | La replica: el mismo valor que la cabecera `X-Instancia` |
| `stack_trace` | Si hubo excepcion, dentro de la misma linea, con la causa raiz primero y recortada a 16 384 caracteres |

Con el perfil `logs-texto` (`SPRING_PROFILES_ACTIVE=logs-texto`) sale el formato de texto de antes, con `[rid=...]`.
El letrero de Spring esta apagado, porque no es JSON.

## Pendiente

- La integracion real con la API de Claude (`ClaudeRoadmapAiClient`) esta
  construida pero sin probar contra la API real. El grupo ya acordo pagar
  la API, asi que solo falta poner `CLAUDE_API_KEY` en el ambiente y
  probarla. Mientras no haya llave, el roadmap se genera con el modo de
  respaldo (ordenacion topologica), que es completamente funcional.
- No hay Flyway/Liquibase: el esquema se maneja con `ddl-auto: update`,
  igual que antes de este cambio. Ver la nota de esquema arriba sobre
  `curso_habilidad`.
