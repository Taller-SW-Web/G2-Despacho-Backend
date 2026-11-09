# G2 — Backend de Despacho y Entrega a Domicilio

Tres aplicaciones Spring Boot independientes y un emisor temporal de Seguridad. El único `compose.yaml` prepara un entorno completamente local con dos bases PostgreSQL separadas. No necesita credenciales ni conexiones a Supabase para arrancar.

El arranque local y las migraciones se comprobaron manualmente el 23/10/2026: Gestión aplicó V1/V2 y Operación V1 a V5. También se verificaron por el Gateway el listado y el detalle de un despacho fallido de prueba y su sincronización con Operación. La suite automatizada completa y el resto de los recorridos siguen pendientes.

Las instrucciones de trabajo están en [AGENTS.md](AGENTS.md) y los cambios de cada entrega en [CHANGELOG.md](CHANGELOG.md).

## Estructura

```text
G2-Despacho-Backend/
├── api-gateway/
├── servicio-gestion-despachos/
├── servicio-operacion-reparto/
├── seguridad-local/
├── compose.yaml
├── init-db.sql
├── .env.example
├── README.md
├── LICENSE
├── .gitignore
└── .gitattributes
```

Cada aplicación conserva su código, pruebas, recursos, `pom.xml`, Maven Wrapper y Dockerfile. El emisor temporal usa Java directamente y tiene su propio Dockerfile. `.env` y los archivos personales del IDE quedan fuera de Git.

## Servicios y puertos

| Servicio | Responsabilidad | Dirección local |
|---|---|---|
| Gateway | Entrada pública, rutas, JWT y CORS | `http://localhost:8080` |
| Gestión | Zonas, tarifas, cotización, programación y consultas de despachos e incidencias | `http://localhost:8081/swagger-ui.html` |
| Operación | Repartidores, vehículos, jornadas, reservas y evidencias | `http://localhost:8082/swagger-ui.html` |
| Seguridad temporal | Emisión de tokens de prueba y claves públicas | `http://localhost:8090` |
| PostgreSQL | Bases `despacho_gestion` y `despacho_operacion` | `localhost:15432` |

Las APIs de negocio se consumen mediante el Gateway, bajo `/api/v1`. Las rutas internas entre microservicios no se publican por el Gateway. Los puertos se exponen únicamente en `127.0.0.1`.

Gestión implementa el seguimiento público por pedido de RT-04 para los canales externos. Consulta estado e hitos en su propia base y omite datos personales y operaciones internas. Su ruta, respuesta y permisos se definen únicamente en el [contrato externo de seguimiento](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md#6-rt-04-seguimiento-para-marketplace-y-chatbot). Las pruebas `ControladorSeguimientoPedidoTests` y `SeguimientoPedidoHttpTests` comprueban permisos, proyección pública, historial y reprogramaciones; la segunda utiliza PostgreSQL desechable con Testcontainers.

Para consultar ejemplos manuales en el Compose local, [pruebas/cargar_seguimientos_local.sql](pruebas/cargar_seguimientos_local.sql) agrega ocho pedidos identificados con `PED-SEG-`: los siete estados y una reprogramación. Solo carga datos ficticios de lectura, sin modificar pedidos existentes, crear eventos ni acreditar el recorrido operativo de Ventas, asignación o entrega. Se ejecuta únicamente en el contenedor PostgreSQL local contra `despacho_gestion`; se puede repetir sin duplicar pedidos ni hitos. Estos ejemplos tienen `es_simulado=false` para comprobar la consulta pública, que excluye los simuladores de F-02.

Verificación local de RT-04 del 06/11/2026: `clean verify` de Gestión aprobó 243 pruebas, incluidas las 11 nuevas de seguimiento, con PostgreSQL desechable. Por el Gateway del Compose se comprobaron los ocho ejemplos con `200`, los rechazos `401`/`403`, el `404` de pedidos inexistentes y simulados y la repetición del cargador sin duplicados. Esto no acredita el recorrido entre los servidores reales de G2, G5, G6 y G7.

En local y con el perfil `render`, la consulta de ruta y el guardado de secuencia de `/api/v1/jornadas/{id}/...` se dirigen a Gestión antes que la ruta genérica de jornadas de Operación. La reasignación pertenece también a Gestión. El Gateway conserva `If-Match`, `Idempotency-Key` y ETag; no habilita por ello las llamadas técnicas públicas ni omite JWT. La UI limita el reordenamiento a rutas continuas mientras se aclara el tratamiento de huecos históricos. Los mecanismos internos de identidad y la integración G6 siguen pendientes de acuerdo y no cambian con este incremento.

## Arranque local

Requisitos: Docker Desktop iniciado, contenedores Linux y arquitectura `amd64` (x86-64) para la imagen PostGIS. Docker compila Java 21 dentro de las imágenes; no hace falta instalar Java o Maven en el equipo para este arranque.

Desde la raíz de este repositorio:

```powershell
docker compose up --build
```

El comando construye e inicia los cinco contenedores y muestra sus registros. `Ctrl+C` los detiene; el volumen conserva la base de datos. La primera construcción descarga las imágenes y dependencias.

Para ejecutarlos en segundo plano y revisar el estado:

```powershell
docker compose up --build -d
docker compose ps
docker compose logs --tail=100 servicio-gestion-despachos servicio-operacion-reparto
docker compose logs -f
```

Para detenerlos:

```powershell
docker compose stop
```

`docker compose down` elimina los contenedores y la red, conservando el volumen. `docker compose down -v` elimina también todos los datos locales de este proyecto; usarlo únicamente si se quiere reconstruir desde cero.

El proyecto se llama `g2-despacho` y utiliza el volumen `datos-postgres-local`. Así se separa de los volúmenes `datos-postgres` de los entornos locales anteriores; no se elimina ninguno de ellos. Si otros contenedores ocupan los mismos puertos, detenerlos antes desde Docker Desktop. La nueva configuración espera una base nueva; no convierte una historia Flyway anterior.

## Variables locales

El Compose contiene valores locales por defecto y funciona sin crear un `.env`. Para cambiar puertos, credenciales ficticias o integraciones, copiar la plantilla si el archivo no existe:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Editar `.env` y usar los mismos comandos de arranque. Un único `.env` raíz configura este Compose; no hace falta crear otro por servicio. Las variables de la terminal pueden prevalecer sobre ese archivo. Nunca publicar `.env` ni la salida completa de `docker compose config`, que puede contener contraseñas.

## Bases y migraciones

`init-db.sql` crea únicamente las dos bases locales durante el primer arranque de un volumen vacío. Cada microservicio aplica sus propias migraciones mediante Flyway:

| Servicio | Migraciones activas | Estado inicial esperado |
|---|---|---|
| Gestión | [V1 original](servicio-gestion-despachos/src/main/resources/db/migration/V1__esquema_original_gestion.sql), [V2 acumulada](servicio-gestion-despachos/src/main/resources/db/migration/V2__actualizacion_esquema_gestion.sql), [V3 reprogramación](servicio-gestion-despachos/src/main/resources/db/migration/V3__reprogramaciones_despacho.sql) y [V4 cierre](servicio-gestion-despachos/src/main/resources/db/migration/V4__cierre_despacho_origen.sql) | Base inicial, resultado durable de reprogramación e índice único del cierre en el historial. |
| Operación | V1 a V6 existentes en `servicio-operacion-reparto/src/main/resources/db/migration` | Tablas, funciones y parámetros de perfiles, flota, jornadas, reservas, proyecciones, evidencias y comandos del repartidor; sin datos de negocio de prueba. |

Flyway queda habilitado para ambas bases locales, con esquema `public`, baseline automática desactivada y limpieza de Flyway desactivada. Hibernate mantiene `ddl-auto=none`. Las modificaciones futuras de Gestión se agregan desde V5; no editar archivos que ya se hayan aplicado a una base persistente. V4 conserva los cierres históricos sin inventar respuestas originales; si existen cierres duplicados del mismo despacho, exige reconciliarlos antes de aplicar el índice.

PostgreSQL usa `postgis/postgis:17-3.6` porque V1 de Gestión conserva `zonas.geom`, `distrito.geom` y sus índices del esquema original. La cobertura actual se resuelve por distrito y código postal; no utiliza esas geometrías. Retirar PostGIS requiere una decisión de esquema posterior. El inicializador de la imagen instala extensiones en `postgres` y `template_postgis`; nuestras bases se crean desde `template1`, y V1 instala PostGIS en `extensions` de Gestión.

Las migraciones anteriores de Gestión, incluidas las dos V3 que colisionaban, se retiraron del classpath. Los archivos originales ya versionados se pueden consultar en el historial Git. V2 reproduce la estructura observada, conservando `cotizaciones.pedido_id` como UUID y sin añadir los `CHECK` más estrictos que no estaban desplegados. No se copiaron datos de negocio de Supabase.

Con las credenciales predeterminadas:

| Campo | Valor |
|---|---|
| Servidor y puerto | `localhost:15432` |
| Usuario | `despacho_local` |
| Contraseña local | `despacho-local-no-produccion` |
| Bases | `despacho_gestion`, `despacho_operacion` |

Comprobar las versiones aplicadas en Gestión:

```powershell
docker compose exec postgres psql -U despacho_local -d despacho_gestion -c "SELECT version, description, success FROM public.flyway_schema_history ORDER BY installed_rank;"
```

Con la revisión actual del repositorio se esperan versiones 1 a 4 en Gestión y 1 a 6 en Operación, todas con `success=true`. La comprobación histórica del 23/10/2026 corresponde a la revisión de ese día; no acredita las migraciones posteriores ni una sincronización automática con Supabase.

## Salud y primeras consultas

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
```

Un estado `UP` confirma la salud informada por la aplicación; no acredita todos los recorridos de negocio. Para comprobar consultas protegidas, obtener un token temporal:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/pruebas/token-usuario' -ContentType 'application/x-www-form-urlencoded' -Body @{ perfil = 'gestor' }).accessToken
Invoke-RestMethod http://localhost:8080/api/v1/distritos -Headers @{ Authorization = "Bearer $token" }
```

El mismo token se puede introducir en `Authorize` de Swagger. Rutas iniciales para revisar: `/api/v1/zonas`, `/api/v1/catalogos/motivos-fallo`, `/api/v1/despachos/pendientes`, `/api/v1/repartidores`, `/api/v1/furgonetas` y `/api/v1/flota/monitoreo`. Las listas de negocio parten vacías; registrar zonas, tarifas y flota antes de probar escrituras y asignaciones.

## Seguridad temporal

Es una herramienta de G2, no Seguridad real de G7. Mantiene validación JWT RS256, permisos y controles de negocio; no tiene cuentas reales. Reproduce el acceso humano documentado por G7 (contrato externo §8.6) con cuentas ficticias para probar ambas apps. No utilizar el emisor temporal para un despliegue público.

| Usuario de prueba (`POST /api/v1/auth/login`) | Clave | Perfil emitido |
|---|---|---|
| `gerardo@despacho.pe` (alias anteriores: `repartidor.prueba`, `mrojas@despacho.pe`) | `Ruta2026!` | `repartidor` |
| `kevin@despacho.pe` | `Ruta2026!` | `repartidor-kevin` |
| `gestor@despacho.pe` (alias: `gestor.prueba`) | `Ruta2026!` | `gestor`, con desafío MFA al iniciar sesión |

| Perfil de prueba | Identificador | Uso |
|---|---|---|
| `gestor` | `11111111-1111-4111-8111-111111111111` | Rol `GESTOR_DESPACHO`. |
| `repartidor` | `22222222-2222-4222-8222-222222222222` | Requiere perfil local activo, vínculo con ese usuario y propiedad del recurso. |
| `repartidor-kevin` | `44444444-4444-4444-8444-444444444444` | Segunda identidad independiente; requiere su propio perfil local activo y vinculado. |
| `sin-rol` | `33333333-3333-4333-8333-333333333333` | Comprobaciones de denegación de permisos. |

Los tokens de usuario duran 15 minutos. Las claves RSA se generan en memoria al arrancar; al reiniciar Seguridad, obtener tokens nuevos y reiniciar los consumidores si conservan las claves en caché. El emisor interno es `http://seguridad-local:8090/api/v1/auth`.

El acceso de ambas apps usa ahora el intercambio JSON de G7: login, solicitud/verificación OTP, renovación rotativa y logout; `/me` consulta el perfil con Bearer. Las rutas y formatos se mantienen en [el contrato externo §8.6](../G2-Despacho-Specs/integraciones/api-contract.md#86-acceso-humano-de-ambas-aplicaciones). El Gateway enruta únicamente esas operaciones humanas a `SEGURIDAD_URL` tanto en el perfil base como en `render`; no publica el emisor de tokens técnicos ni el buzón de pruebas.

La ruta humana elimina valores repetidos de `Access-Control-Allow-Origin` y `Access-Control-Allow-Credentials` con `DedupeResponseHeader` y estrategia `RETAIN_UNIQUE`. Si Seguridad también añade CORS, evita que el navegador bloquee una respuesta por recibir dos veces el mismo origen. Se mantiene la lista de orígenes autorizados del Gateway, el estado HTTP y el cuerpo originales. La regresión `RutasSesionRenderTests` reproduce esta combinación con un destino HTTP simulado y comprueba ambos frontends, preflight, errores y rechazo de orígenes ajenos. Publicar el cambio requiere reconstruir/desplegar el Gateway; no requiere nuevas variables ni cambios en los frontends.

En Compose, el gestor recibe un desafío de cinco minutos. Cada solicitud genera un código de seis dígitos para su buzón simulado, con tres intentos y un máximo de tres envíos por usuario cada quince minutos. El proxy de desarrollo del frontend permite consultar el buzón con `POST /pruebas/codigo-otp` y `challengeToken`; no es una API real de G7. Reenviar sustituye el código sin extender el desafío. Las identidades de repartidor acceden sin MFA obligatorio. El emisor conserva en memoria desafíos, sesiones y familias de refrescos; reiniciar invalida ese estado.

Logout es idempotente, no exige un JWT vigente y bloquea la renovación. Reutilizar un refresco ya rotado revoca su familia. Los JWT ya emitidos pueden seguir siendo aceptados por los servicios que validan firma y vigencia localmente hasta su vencimiento; no se ha añadido revocación distribuida.

La primera fase se preparó sin ejecutar pruebas por indicación del usuario. En la verificación posterior autorizada aprobaron las comprobaciones del emisor local y `clean verify` del Gateway con 30 pruebas, además del acceso en navegador usando el Compose que el usuario ya había iniciado. La ruta humana se registra desde `RutasAccesoSeguridad`, común a los perfiles; si Seguridad no está configurada en modo demo, no se registra un destino vacío. El kit G7 recibido se identifica como revisión local no publicada: falta contrastar servidor, cuentas, activación, OTP y CORS antes de acreditar integración real.

Para un token de servicio de cotización:

```powershell
$tokenServicio = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/api/v1/auth/token' -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type = 'client_credentials'; client_id = 'modulo-marketplace'; client_secret = 'despacho-local-no-produccion'; scope = 'cotizaciones:calcular' }).accessToken
```

El secreto es ficticio y solo pertenece al emisor local. Los clientes de prueba incluyen `modulo-marketplace`, `modulo-chatbot`, `modulo-ventas` y `modulo-despacho`. Gestión tiene configurado el cliente técnico para consultar incidencias y reservar/liberar capacidad y publicar proyecciones en Operación.

## Verificación F-02 publicada, sin escrituras

Con un despacho ya asignado en un entorno autorizado, `pruebas/verificar_f02_publicado.py` comprueba salud, rechazo anónimo, cola autorizada, ruta de Gestión, ruta propia de F-03, coincidencia de jornada/secuencia/ocupación y rechazo de consulta desde un segundo repartidor (`404` conforme al contrato). Solo ejecuta GET por el Gateway; no carga `.env`, no crea recursos, no sigue redirecciones ni imprime JWT o cuerpos de respuesta.

Proporcionar por un canal seguro las variables `F02_GATEWAY` (origen HTTPS; HTTP solo en loopback), `F02_GESTOR_TOKEN`, `F02_REPARTIDOR_TOKEN`, `F02_OTRO_REPARTIDOR_TOKEN`, `F02_DESPACHO` y `F02_JORNADA`. Las tres sesiones deben ser distintas y los dos repartidores deben estar activos/vinculados. Declarar también `F02_SHA_BACKEND`, `F02_SHA_FRONTEND` y `F02_SHA_DOCS` de la revisión publicada para trazabilidad; el verificador no comprueba por sí mismo esos SHA.

Desde la raíz, con Python 3:

```bash
python3 -B pruebas/verificar_f02_publicado.py > /tmp/opencode/f02-publicado.json
python3 -B -m unittest discover -s pruebas -p 'test_verificar_f02_publicado.py' -v
```

El primer comando requiere el directorio de salida creado previamente; puede elegirse otra ruta privada. Devuelve `0` si todas las comprobaciones pasan, `1` si falla alguna y `2` si falta configuración. Ejecutarlo antes de iniciar traslado y después de converger la proyección de F-03. El reporte no acredita login/MFA, variación de capacidad antes/después, captura de ambas apps ni F-04; conservar esa evidencia aparte. Las pruebas unitarias usan dobles explícitos, no el despliegue.

## Guías locales y planes de cotización

| Documento | Contenido |
|---|---|
| [Pruebas desde Postman](servicio-gestion-despachos/docs/pruebas-postman-local.md) | Arranque, tokens, zona, tarifa y cotización con los productos simulados actuales. |
| [Conexión de pgAdmin](servicio-gestion-despachos/docs/conexion-pgadmin-local.md) | Acceso desde Docker o el equipo, red compartida y consulta de tablas. |
| [Plan de persistencia de cotizaciones](servicio-gestion-despachos/docs/plan-persistencia-cotizaciones.md) | Escritura del histórico exacto antes de responder; vinculación con Ventas aplazada. |
| [Plan de integración con Productos](servicio-gestion-despachos/docs/plan-integracion-productos.md) | Selección del cliente HTTP real, credenciales propias, validación de lotes y pruebas. |

Los planes describen trabajo pendiente y referencian los contratos de Specs. No acreditan la implementación de la persistencia ni de la consulta real a Productos en la cotización.

## Probar desde el administrador

El Compose levanta únicamente los servicios del backend y PostgreSQL. El frontend se ejecuta por separado desde la raíz de `G2-Despacho-Frontend`, con Node.js 20 o superior y npm 10 o superior:

```powershell
npm install
npm run dev:administrativo
```

`npm install` prepara las dependencias al comenzar o cuando cambian. El administrativo queda en `http://localhost:5173`. Para configurar explícitamente su conexión, copiar `apps/despacho-administrativo/.env.example` a `.env` dentro de esa aplicación, si no existe, y establecer:

```dotenv
VITE_API_BASE_URL=http://localhost:8080
VITE_DEMO_SIN_AUTENTICACION=false
```

Si se cambia `GATEWAY_PORT`, ajustar también esta URL. Reiniciar Vite al modificar sus variables. Este `.env` del frontend es independiente del `.env` raíz del backend.

Recorrido inicial, usando siempre el origen `localhost` para conservar la sesión:

1. Abrir `http://localhost:5173/desarrollo/f01`, seleccionar **Gestor de Despacho** y pulsar **Generar sesión de prueba**. La misma sesión permite navegar a las otras funcionalidades administrativas. Renovarla desde ese probador cuando venza.
2. En **Zonas y tarifas**, crear una zona activa y configurar una tarifa si se desea probar cotizaciones.
3. En **Repartidores**, registrar los perfiles de prueba. Desde **Más acciones > Vincular cuenta**, asociar `22222222-2222-4222-8222-222222222222` a Gerardo y `44444444-4444-4444-8444-444444444444` a Kevin. Cada identidad solo puede vincularse a un perfil. El emisor no crea estos registros en la base automáticamente.
4. En **Vehículos**, registrar una furgoneta disponible con capacidades positivas. Por ejemplo: 100 kg, 1 m³ y 10 paquetes.
5. En **Monitoreo de flota**, pulsar **Abrir jornada**, seleccionar el repartidor vinculado, el vehículo, la zona y la fecha de hoy. Estar vinculado por sí solo no lo hace disponible para asignación.
6. En **Programación**, usar **Generar pedido de prueba** y asignarlo a un candidato con jornada activa y capacidad suficiente. Los pedidos simulados se guardan en PostgreSQL local y comienzan pendientes de asignación.
7. En **Entregas fallidas**, consultar los fallos existentes y abrir **Ver detalle**. El botón de Programación no genera fallos: para comprobar listado y detalle hacen falta incidencias de prueba con historial y referencias coherentes de repartidor y jornada. Una base nueva muestra el listado vacío.

### Verificar F-05 y F-06 sin Seguridad real

El botón **Generar sesión de prueba** del probador administrativo utiliza el emisor temporal local, sin crear cuentas en Seguridad real. En F-05 se registra el perfil local y se asocia manualmente el UUID de prueba del repartidor. El backend conserva la autorización; las pruebas identifican expresamente los JWT simulados.

Con los recursos del recorrido anterior, comprobar registro/edición/baja de repartidores, vinculación manual, registro/edición/estados de furgonetas y apertura/detalle/cierre de jornadas. Una edición de capacidad solo afecta jornadas futuras. La baja de un perfil o el retiro de una furgoneta cierran sus jornadas vacías y auditan cada cierre con el actor y las instantáneas anterior/nueva en la misma transacción. Si falla esa auditoría, también se revierte la baja o el retiro; las repeticiones no duplican el cierre.

Las reservas activas y los despachos en curso siguen bloqueando la baja y el retiro. Una recepción pendiente no libera capacidad por anticipado. La suite `servicio-operacion-reparto` verifica estos casos con PostgreSQL desechable mediante `.\mvnw.cmd clean verify`; requiere Docker disponible y no utiliza Supabase. La suite de navegador `npm run test:flota:e2e`, en el frontend, usa APIs simuladas y no sustituye esa comprobación de persistencia.

### Probar la app del repartidor (F-03)

Tras los pasos 1 a 6, ejecutar `npm run dev:repartidor` en otra terminal del frontend (puerto 5174) e ingresar con `mrojas@despacho.pe` / `Ruta2026!`; ese usuario corresponde al perfil vinculado en el paso 3. La ruta muestra los despachos asignados. **Iniciar traslado** y **Cerrar jornada** funcionan sin Storage; **Confirmar entrega** y **Marcar como fallido** necesitan la foto en el bucket privado, por lo que sin las variables `EVIDENCIAS_*` responden `503`. El cierre deja los pendientes como `FALLIDO`/`NO_INTENTADO`, visibles después en **Entregas fallidas** del administrador.

Para revisar la interfaz sin backend, la app admite `VITE_MODO_DEMO=true`, con datos en memoria que no llegan a PostgreSQL.

### Recepción y reprogramación (F-04)

En la comprobación local se preparó manualmente un fallo por `CLIENTE_AUSENTE`, con un intento consumido y retorno pendiente; se verificaron listado, detalle, historial y sincronización con Operación. Esa preparación de datos no forma parte del arranque automático. Las fotografías necesitan Storage y una evidencia asociada. Gestión implementa recepción y reprogramación (ES-F04-03/04); el cierre ES-F04-05 mantiene su alcance pendiente.

Los registros creados o preparados durante las pruebas permanecen en el volumen local de Docker. No se incluyen en los commits ni se copian a otros equipos: una instalación nueva parte de las migraciones y sus catálogos. `docker compose down -v` elimina también esos registros.

## Integraciones pendientes y frontends

- Productos y Ventas no se levantan en este Compose. El perfil normal de Gestión consulta Productos por HTTP tanto para cotizar como para recibir pedidos. Configurar `PRODUCTOS_URL` y una credencial propia de `modulo-despacho`: `PRODUCTOS_CLIENT_SECRET` para obtenerla de Seguridad o `PRODUCTOS_TOKEN_SERVICIO` para suministrarla externamente. G6 exige audiencia `api-productos` y scope `productos:fisicos:leer`. La ausencia de esa integración produce indisponibilidad; las consultas de solo cobertura siguen siendo locales. Las notificaciones a Ventas pueden quedar pendientes hasta configurar destino e identidad técnica.

### Cotizaciones F-01 e integración con G6

El perfil normal usa el cliente HTTP compartido `ClienteDatosFisicosHttp`. Las pruebas de contrato e integración utilizan un doble HTTP explícito y PostgreSQL desechable; no acreditan autorización G7 ni despliegue de G6. El perfil adicional `productos-simulados` selecciona expresamente el catálogo local solo para cotizaciones de prueba y marca sus instantáneas como simuladas. Para usarlo en Compose, cambiar explícitamente `SPRING_PROFILES_ACTIVE` de Gestión mediante una configuración local; no es el perfil por defecto.

Los parámetros operativos son `LIMITE_COTIZACIONES_POR_MINUTO` (60 por defecto), `PRODUCTOS_URL`, `PRODUCTOS_CLIENT_ID` (`modulo-despacho`), `PRODUCTOS_CLIENT_SECRET` y `PRODUCTOS_TOKEN_SERVICIO`. Las plantillas contienen únicamente nombres y marcadores, nunca credenciales reales. No se reenvía el JWT del canal a G6. El transporte actual utiliza un segundo para conexión y 1.5 segundos para lectura; el acuerdo de rendimiento y caché con los equipos externos sigue pendiente de medición.

Las reglas públicas se mantienen en el contrato externo de Specs: el costo final se eleva al siguiente múltiplo de medio sol y el volumen informado a tres decimales. El cálculo utiliza el volumen exacto. Cada cotización exacta exitosa confirma una instantánea antes de responder; su detalle conserva perfiles, tarifa, valores exactos y ajuste de redondeo. El plazo actual cuenta lunes a viernes en `America/Lima`; feriados y horas de corte requieren el acuerdo operativo pendiente.

Las migraciones V8–V10 amplían la precisión de pesos históricos, añaden control optimista a zonas y guardan la identidad de reintentos administrativos. Se prueban con bases temporales. El perfil `render` conserva Flyway deshabilitado por defecto: la aplicación de estas migraciones a una base persistente debe prepararse como despliegue independiente.

La configuración admite reintentos identificados y control de versión, conforme al contrato de Gestión en Specs. El frontend consumidor debe conservar `soloCodigosPostales` al editar y puede enviar `versionZona` al cambiar estado. Las operaciones anteriores siguen admitidas. Las pruebas backend se ejecutan desde `servicio-gestion-despachos` con `./mvnw clean verify`; requieren Docker. Los reportes se generan en `target/surefire-reports`.
- Las fotografías requieren un bucket privado y `EVIDENCIAS_STORAGE_URL`, `EVIDENCIAS_BUCKET` y `EVIDENCIAS_CLAVE_SERVICIO` en Operación. Vacíos, esos recorridos no están disponibles; no se sustituye Storage por un servicio local.
- Los frontends pueden usar `VITE_API_BASE_URL=http://localhost:8080` y un token temporal mediante su configuración de desarrollo. CORS admite los puertos 5173 y 5174. Si el frontend necesita refrescar tokens, debe usar la configuración vigente de su propio repositorio.
- La información de errores incorpora un identificador de correlación; buscarlo en `docker compose logs` para seguir una petición entre servicios.

## Despliegues existentes

Los Dockerfiles y perfiles `application-render.yaml` de los tres backends se conservan. El único Compose está destinado al entorno local y no modifica Render ni Supabase.

Las plantillas de despliegue están ahora dentro de sus aplicaciones: [Gateway](api-gateway/.env.example), [Gestión](servicio-gestion-despachos/.env.example) y [Operación](servicio-operacion-reparto/.env.example). Configurar las variables en el panel de cada servicio. Cada microservicio utiliza su propia conexión de base de datos; el Gateway recibe las URLs de los servicios y no necesita credenciales PostgreSQL.

El perfil `render` de ambos servicios deja Flyway desactivado por defecto, pero la variable `SPRING_FLYWAY_ENABLED` del despliegue puede sobrescribir ese valor. Comprobar las variables efectivas antes de desplegar esta historia V1/V2 sobre un Supabase existente. Adoptar Flyway allí requiere comparar el esquema y conciliar el historial; una posible baseline 2 de Gestión es una tarea posterior. Hibernate mantiene `ddl-auto=none`.

Las llamadas internas entre Gestión y Operación usan el token de servicio de `modulo-despacho`. G7 deriva la audiencia de los scopes concedidos y, sin scopes internos, no incluye `api-despacho`; por eso los validadores de ambos servicios aceptan ese cliente propio con las audiencias que G7 emita. El resto de clientes técnicos sigue necesitando `api-despacho`. Es una excepción temporal: retirarla cuando G7 emita esa audiencia para `modulo-despacho`. En Render, Gestión necesita `OPERACION_CLIENT_SECRET`; sin él, asignar responde `503 DESP_ERROR_OPERACION_NO_DISPONIBLE`.

Los scripts heredados F-03/F-05/F-06 de Operación se conservaron en `servicio-operacion-reparto/src/test/resources/flota/compatibilidad`. Sus pruebas los cargan como recursos. No son migraciones automáticas de una base nueva ni deben volver a aplicarse a ciegas al entorno publicado. Los datos F-04 usados por una prueba están en `src/test/resources/flota/datos-demo-f04.sql` del mismo servicio.

### Consultas periódicas de salud desde el PC

[herramientas/mantener_backends_activos.py](./herramientas/mantener_backends_activos.py) consulta directamente `/actuator/health` del Gateway, Gestión y Operación cada cinco minutos. Requiere Python 3.9 o posterior, sin paquetes adicionales, cuentas ni `.env`. Desde la raíz del backend:

```powershell
python herramientas/mantener_backends_activos.py
```

Mantener la terminal abierta; `Ctrl+C` detiene el script. El PC debe tener conexión y permanecer encendido, sin suspensión. Las peticiones son secuenciales, con una espera máxima predeterminada de 75 segundos para permitir arranques lentos; no se superponen. Un `429` u otra respuesta con `Retry-After` retrasa la siguiente consulta de ese servicio. No se guardan tokens ni cuerpos de respuestas, y una página HTML de arranque no se informa como salud `UP`.

Para cambiar el intervalo, comprobar una sola ronda o sustituir las URLs públicas:

```powershell
python herramientas/mantener_backends_activos.py --intervalo 300 --timeout 75
python herramientas/mantener_backends_activos.py --una-vez
python herramientas/mantener_backends_activos.py --url https://HOST-REAL/actuator/health
```

`--url` se puede repetir y sustituye los tres destinos predeterminados. Confirmar las URLs con el responsable del despliegue si cambian. `--una-vez` termina con código `0` cuando todos responden `200` y `status=UP`, o `1` si alguno falla. El intervalo mínimo es 60 segundos. Para verificar el consultor sin llamar a Render: `python -B -m unittest discover -s pruebas -p test_mantener_backends_activos.py -v`.

Estas consultas reducen la inactividad de los servicios; no garantizan disponibilidad frente a reinicios, límites del plan o fallos de red. En el plan gratuito, las 750 horas mensuales se comparten por workspace: mantener servicios encendidos también consume ese cupo. Referencia: [límites de Render](https://render.com/docs/free). Tampoco corrigen errores SQL, migraciones pendientes ni permisos de negocio. No se instala una tarea del sistema ni se inicia un proceso permanente automáticamente.

## Formato del código

Java usa google-java-format en estilo AOSP (4 espacios por nivel, 100 columnas) mediante Spotless; la configuración está en el `pom.xml` de cada servicio y en `.editorconfig`. Dentro de cada servicio:

```powershell
.\mvnw.cmd spotless:apply  # Aplica el formato
.\mvnw.cmd spotless:check  # Comprueba el formato sin modificar archivos
```

`seguridad-local` no es un módulo Maven y queda fuera de estos comandos.

## Pruebas y contratos

La implementación backend de ES-F04-03 y sus límites se describen en [verificación de recepción en centro](servicio-gestion-despachos/docs/ES-F04-03-verificacion.md). Esa especificación reutiliza V1/V2. ES-F04-04 añade V3 para conservar la respuesta de reprogramación junto con su historial y evento; no modifica Operación ni su base. Las verificaciones usan bases desechables. Los datos históricos sin referencias completas requieren reconciliación verificable; los comandos los rechazan sin adivinar reservas. La trazabilidad vigente de reprogramación se mantiene en [su evidencia SDD](../G2-Despacho-Specs/especificaciones/ES-F04-04-ReprogramarDespachoFallido/evidence.md).

ES-F04-05 añade V4 y `POST /api/v1/despachos/{id}/devolucion-origen`, con resultado original en el historial y evento para despachos reales. Conserva la recepción y su liberación; no llama a Operación para aceptar el cierre. Su seguimiento está en [la evidencia SDD de cierre](../G2-Despacho-Specs/especificaciones/ES-F04-05-CerrarDespachoComoDevueltoAOrigen/evidence.md).

Para el recorrido de navegador de ES-F04-04, con Docker disponible, ejecutar desde Gestión en PowerShell:

```powershell
.\mvnw.cmd test-compile dependency:build-classpath '-Dmdep.outputFile=target/classpath-revision.txt'
$classpathF04 = 'target/test-classes;target/classes;' + (Get-Content target/classpath-revision.txt -Raw).Trim()
java -cp $classpathF04 com.modulodespacho.gestiondespachos.servicio_gestion_despachos.ReprogramacionNavegadorPruebas
```

El arranque crea PostgreSQL desechable, aplica Flyway y reutiliza el escenario sintético de recepción/reprogramación con reloj `2026-10-25T03:30Z` (24/10 en Lima). Expone Gestión en `18085` con JWT controlado; no incorpora esa identidad al artefacto productivo. Tras `REVISION_F04_LISTA`, ejecutar `npm run test:f04:e2e` desde frontend. Para repetir el recorrido hay que reiniciar esta instancia, porque la prueba acepta la decisión real. Se cierra con Ctrl+C, al crear `target/detener-revision-f04`, o después de 15 minutos. Antes de otro arranque, retirar únicamente ese archivo de señal si existe. Gateway, Seguridad, Operación y Ventas reales no quedan acreditados por este recorrido.

Para ES-F04-05, usar el mismo classpath con `java -cp $classpathF04 com.modulodespacho.gestiondespachos.servicio_gestion_despachos.CierreNavegadorPruebas`. Esperar `REVISION_CIERRE_F04_LISTA` en el puerto `18086` y ejecutar `npm run test:f04:cierre:e2e` desde frontend. Tiene su propia base desechable y señal `target/detener-revision-cierre-f04`; también termina a los 15 minutos. El recorrido verifica pérdida de respuesta, recuperación exacta, conservación de filtros y accesibilidad/adaptación en navegador.

Gestión ejecuta su procesador de liberaciones con `RECEPCION_PROCESADOR_HABILITADO=true`, revisión de pendientes cada `RECEPCION_INTERVALO_PROCESADOR_MS=1000` y calendario `RECEPCION_ESPERAS=PT30S,PT2M,PT10M,PT30M,PT1H` (duraciones ISO 8601). Necesita la URL de Operación y la credencial técnica de `modulo-despacho` (token de servicio emitido por Seguridad; las rutas internas no exigen scopes ni la audiencia `api-despacho`); reutiliza `OPERACION_REPARTO_URL`, `OPERACION_CLIENT_ID`, `OPERACION_CLIENT_SECRET` o `OPERACION_TOKEN_SERVICIO`. Los secretos se configuran fuera de Git. Sin confirmación válida, conserva el retorno físico y la liberación pendiente; los errores de autorización o datos se señalan para revisión y se reintentan cada hora. Desactivar el procesador conserva las solicitudes, pero no las entrega hasta reactivarlo.

Cada backend tiene su propio Maven Wrapper y sus pruebas. Para ejecutarlas manualmente, desde el directorio correspondiente:

```powershell
.\mvnw.cmd clean test
```

Para ejecutar Maven Wrapper fuera de Docker se necesita Java 21. Las suites de integración requieren Docker y crean bases aisladas con Testcontainers. Gestión necesita la imagen PostGIS definida en su configuración de pruebas. Nunca apuntar esas suites a Supabase ni a una base compartida, porque algunas crean, modifican o limpian tablas. `clean` evita conservar migraciones antiguas en `target/classes`.

Los Dockerfiles omiten las pruebas durante la construcción: `docker compose up --build` no ejecuta estas suites. El recorrido manual registrado arriba tampoco sustituye sus resultados.

Las pruebas de Seguridad temporal permanecen en `seguridad-local/pruebas`. Se retiraron los Compose y verificadores específicos de las demos; los datos y adaptaciones usados por pruebas se conservaron dentro de Operación.

## Prueba de humo reutilizable de F-04

Desde la raíz del backend, con Python 3.10 o posterior y Docker, construir los tres servicios con sus Maven Wrapper y `clean verify`. Luego ejecutar:

```powershell
python pruebas/verificar_humo_f04.py --local
```

El comando reutiliza `pruebas/compose.f02.yaml` con un nombre de proyecto único, bases desechables y Seguridad local. Usa los puertos `18080` y `18090`, que deben estar disponibles, y retira sus contenedores al terminar. Comprueba salud del Gateway y el listado de fallidos por la ruta pública, con `401` sin token, `403` sin permiso y `200` con gestor. La consulta permite una lista vacía. El informe JSON queda en `pruebas/resultados/humo-f04.json`, fuera de Git. El proceso termina con código `1` si falla la preparación o alguna comprobación.

Para un Gateway existente, preparar en el entorno del proceso `HUMO_TOKEN_GESTOR` y `HUMO_TOKEN_SIN_PERMISO` con dos tokens humanos vigentes del emisor que ese despliegue acepta. El primero debe tener `GESTOR_DESPACHO` y el segundo carecer de ese rol. Con G7, obtener las sesiones mediante su flujo de acceso y MFA. Ejecutar, sustituyendo el origen de ejemplo:

```powershell
python pruebas/verificar_humo_f04.py --gateway-url https://gateway-del-equipo.example --version-desplegada revision-confirmada
```

Las peticiones son de lectura y los tokens se reciben por variables de entorno. El informe guarda el estado HTTP y la estructura esperada, sin cuerpos de despachos ni credenciales. `commitVerificador` identifica el código de la herramienta y `versionDesplegada` registra la revisión indicada por el responsable del despliegue. Cada servicio sigue validando sus permisos. La ejecución local acredita el Gateway y los proveedores del entorno desechable con Seguridad temporal; la aceptación con G7 se registra al repetir la prueba con sus sesiones reales.

Para comprobar que la herramienta falla ante acceso anónimo permitido, respuestas inválidas, filtración de contenido o redirecciones:

```powershell
python -m unittest discover -s pruebas -p test_verificar_humo_f04.py -v
```

## Prueba de contrato de recepción F-04 por Gateway

Con los mismos requisitos y JAR construidos de la prueba de humo, desde la raíz del backend:

```powershell
python pruebas/verificar_contrato_recepcion_f04.py
```

El comando prepara datos sintéticos en su propio proyecto Compose desechable. Crea una zona, un perfil y un vehículo de prueba; abre una jornada, simula y asigna un despacho y prepara `FALLIDO/NO_INTENTADO` mediante la API interna real. Esa preparación acredita el estado requerido para recibir; la captura de un fallo con foto desde F-03 se verifica aparte.

Comprueba el contrato vigente de [recepción en Gestión](../despacho-docs/integraciones/api-contract-gestion-despachos.md#521-confirmar-recepción-en-centro--es-f04-03) pasando por Gateway: `401/403`, seis variantes inválidas con `400`, incidencia o versión obsoletas con `409`, recepción `202` con sello `false` sin observación y repetición exacta. Verifica el código de error, Problem Details, `Cache-Control: no-store` en el `202` y ausencia de escrituras ante rechazos. Tras confirmar la liberación real en Operación, exige una recepción, un historial, una solicitud y una reserva original liberada, sin incrementar intentos ni generar eventos a Ventas. La ocupación de la jornada sintética pasa de 2 kg, 0,01 m³ y un paquete a cero; repetir conserva respuesta, fecha de liberación y conteos.

Solo admite su entorno desechable; utiliza `18080/18090` y lo retira al finalizar. No apuntarlo a bases compartidas ni ejecutarlo a la vez que la prueba de humo u otro runner que use esos puertos. El informe `pruebas/resultados/contrato-recepcion-f04.json` queda fuera de Git, con resultados y datos sintéticos antes/después, sin tokens. Termina con código `1` ante un incumplimiento o error de preparación. G7 real, frontend, reinicios y aceptación en nube siguen pendientes.

Las comprobaciones propias de ambos verificadores se ejecutan con:

```powershell
python -m unittest discover -s pruebas -p 'test_verificar_*.py' -v
```

## Verificación integrada de rutas F-02

La rama `feature/f02-rutas-transferencia-segura` añade Gestión V5 (resultado durable de secuencia y unicidad diferida de posiciones), V6 (intenciones/resultados y cerco de reasignación), V7 (cancelaciones durables por anulación) y Operación V7 (transferencias atómicas de capacidad; V6 ya pertenece a comandos F-03). No editar migraciones aplicadas ni activar Flyway sobre bases compartidas para probar. Las futuras migraciones empiezan en Gestión V8 y Operación V8.

Construir los tres servicios con `./mvnw clean verify` y, desde la raíz del backend, ejecutar:

```bash
python3 pruebas/verificar_f02_rutas.py
```

Requiere Python 3.9+, Docker Compose, los JAR verificados, el frontend hermano `../G2-Despacho-Frontend` con dependencias instaladas y Chromium de Playwright. Reserva `18080`, `18090` y `4186`. El script no carga `.env`: crea un proyecto Compose único, PostgreSQL con almacenamiento temporal, dos bases separadas y datos sintéticos. Usa Gateway/Gestión/Operación reales y Seguridad temporal de G2, crea dos repartidores/jornadas y despachos simulados y ejecuta `npm run test:f02:rutas:e2e` del frontend (ruta y reasignación). Al terminar elimina únicamente los contenedores/red de ese proyecto; no usa ni borra volúmenes del desarrollo.

El recorrido verifica teclado, descarte, guardado con respuesta perdida y llegada del orden a F-03; también reasigna desde el modal, recupera la misma solicitud tras perder su respuesta y comprueba las dos rutas móviles y capacidades reales. Productos, Ventas y Seguridad real de G7 no quedan acreditados. Gestión coordina la transferencia con un cerco durable frente a traslado/reordenamiento y recuperación hacia adelante; nunca libera una reserva transferida por un fallo local. El procesador de programación existente retoma las intenciones pendientes tras vencer su arrendamiento de 60 segundos. Sin Operación o sin poder confirmar localmente, el despacho permanece bloqueado para no entregar desde una asignación incierta. Estado y resultados: [ES-F02-05](../despacho-docs/especificaciones/ES-F02-05-ConsultarReordenarSecuenciaRuta/evidence.md), [ES-F02-06](../despacho-docs/especificaciones/ES-F02-06-ReasignarDespachoRepartidor/evidence.md).

**Cancelación ES-F02-07:** el mismo runner solicita JWT técnico de Ventas al emisor temporal y verifica cancelación de pendiente/asignado, retiro de cola/ruta y capacidad sin doble liberación. Después prepara un fallo por la API interna real (no acredita captura de evidencia en campo), anula el pedido, recibe físicamente el paquete, verifica liberación F-06 y rechazo de reprogramación y cierra por F-04. No carga credenciales reales ni publica rutas internas en Gateway.

`CancelarDespacho` persiste intención/cerco antes de liberar una reserva de `ASIGNADO`; confirma `200` solo tras liberación y guardado local. Los fallos/timeout conservan el cerco y se recuperan con la misma clave/cuerpo y reserva. No se debe borrar la intención ni recrear una reserva para compensar. El procesador de programación retoma pendientes con lease vencido; una referencia o motivo remoto inconsistente deja bloqueo para revisión operativa. En `FALLIDO`, solo registra la marca temporal y responde `202`; la liberación corresponde a recepción física. No se añade pantalla de cancelación administrativa. Contrato y evidencia: [Común §7.2](../despacho-docs/integraciones/api-contract.md#72-cancelar-por-anulación-del-pedido), [ES-F02-07](../despacho-docs/especificaciones/ES-F02-07-CancelarDespachoPorAnulacion/evidence.md).

## Integración continua

La preparación de identidad F-05/F-06 se registra en [Specs](../despacho-docs/docs/integracion-seguridad-f05-f06.md). Operación consume el descubrimiento del origen `SEGURIDAD_URL` y valida RS256, issuer y vigencia; además exige `iat` original, `jti`, `sub` UUID y `roles` como arreglo para personas (admite `[]`), o audiencia `api-despacho` para servicios. Las rutas `/internal` de Gestión y Operación solo comprueban que el token sea de servicio, de `modulo-despacho`, y válido en firma, emisor, vencimiento y audiencia; no exigen scopes internos ni se solicitan al obtener el token. Los scopes externos (`cotizaciones:calcular`, `despachos:crear`, `despachos:cancelar`, `seguimientos:leer`, `productos:fisicos:leer`, `ventas:pedidos:estado`) se mantienen. La cuenta sin roles de un repartidor se autoriza por su perfil local activo/vinculado. No se añade un rol global ni se consulta la base de Seguridad.

Para verificar criptografía sin cuentas ni bases externas, desde `servicio-operacion-reparto` ejecutar `./mvnw.cmd "-Dtest=ValidadorTokenDespachoTests,ValidadorPerfilRepartidorTests,IntegracionJwtSeguridadTests,GestorJornadasTests" test`. El servidor JWKS de esas pruebas usa loopback y claves efímeras; no acredita autenticación contra G7. El registro/vínculo manual no comprueba activación global; cuentas reales y eventos/reconciliación permanecen pendientes. La suite completa de `clean verify` requiere Docker y PostgreSQL desechable.

[CI/CD backend](.github/workflows/ci.yaml) se ejecuta en los PR dirigidos a `develop` o `main`, en los cambios integrados a esas ramas y bajo ejecución manual desde Actions. Usa Java 21 y comprueba los tres servicios por separado con Maven Wrapper y `clean verify`. Las pruebas existentes de integración crean PostgreSQL/PostGIS temporal mediante Testcontainers; el workflow no carga `.env`, no utiliza Supabase y no inicia el Compose de desarrollo. Los informes de Maven se conservan siete días en los artefactos de cada ejecución.

Una cuarta comprobación compila y ejecuta las pruebas propias de `seguridad-local`, que levantan su emisor aislado con puerto efímero. El workflow no publica releases y no crea tags.

### Pipeline CI/CD

```
PR / push a develop o main
  1. Compilar y probar   verificador F-02 · Pruebas (api-gateway | gestión | operación) · seguridad-local
  2. Construir imágenes  Imagen Docker (api-gateway | gestión | operación | seguridad-local)
  3. Compuerta           CI backend verde
  4. Desplegar           solo push o ejecución manual en main  ->  Render (Operación, Gestión, Gateway)
  5. Verificar           /actuator/health de los tres servicios (herramientas/mantener_backends_activos.py)
```

- **Compilar y probar:** `clean verify` por servicio, con PostgreSQL/PostGIS temporal (Testcontainers).
- **Construir imágenes:** cada Dockerfile se construye con `docker build` en cada ejecución, de modo que un Dockerfile roto falla antes de integrar. En `main` las imágenes de Gateway, Gestión y Operación se publican en GHCR (`ghcr.io/<organización>/<repositorio>/<servicio>`) con las etiquetas del SHA del commit y `latest`. `seguridad-local` se construye pero nunca se publica ni se despliega: es un emisor de pruebas que no debe usarse en un despliegue público.
- **Compuerta:** el job `CI backend verde` falla si cualquier etapa anterior no terminó en `success`. El despliegue depende únicamente de él.
- **Desplegar:** en `main`, con el entorno `produccion` de GitHub, se llama a los deploy hooks de Render con el SHA del commit (`&ref=<SHA>`), primero Operación y Gestión y al final el Gateway. Solo hay un despliegue a la vez (`concurrency`) y nunca se cancela uno en curso.
- **Verificar:** tras una espera inicial, se reutiliza `herramientas/mantener_backends_activos.py --una-vez` hasta que los tres servicios respondan `200` y `status=UP`, con reintentos de hasta unos 15 minutos. Esto acredita que los servicios están disponibles tras el despliegue; no identifica la versión (el endpoint de salud no la expone).

#### Despliegue continuo: configuración necesaria

El despliegue no funciona hasta configurar esto en GitHub y Render (Settings > Secrets and variables > Actions). Mientras falten los secretos, el job `Desplegar en Render y verificar` falla en `main` con un mensaje que indica cuál falta.

| Secreto | Valor |
|---|---|
| `RENDER_DEPLOY_HOOK_OPERACION` | Deploy hook de `taller-servicio-operacion-reparto` (Render > servicio > Settings > Deploy Hook) |
| `RENDER_DEPLOY_HOOK_GESTION` | Deploy hook de `taller-servicio-gestion-despachos` |
| `RENDER_DEPLOY_HOOK_GATEWAY` | Deploy hook de `taller-api-gateway` |

1. En Render, en cada servicio, poner **Auto-Deploy en `Off`** (o `After CI Checks Pass` si se prefiere que Render espere al CI). Si queda en `On`, cada push despliega sin esperar a las pruebas y el job duplica el despliegue.
2. Crear en GitHub el entorno `produccion` (Settings > Environments). Opcional: añadir *Required reviewers* para exigir aprobación manual antes de desplegar.
3. Las URLs verificadas son las predeterminadas de `herramientas/mantener_backends_activos.py`; si cambian en Render, actualizar esa herramienta.
4. Para redesplegar sin un commit nuevo: Actions > CI/CD backend > *Run workflow* sobre `main`. Las ejecuciones manuales sobre otras ramas solo compilan y prueban.

Para exigir el resultado antes de integrar, configurar la protección de `develop` y `main` en GitHub y seleccionar la compuerta `CI backend verde` cuando haya aparecido en una ejecución. Resume las pruebas de los tres servicios, de `seguridad-local`, del verificador F-02 y la construcción de las imágenes, y no depende de los nombres de la matriz. Si se prefiere exigirlas por separado, también existen `Pruebas (api-gateway)`, `Pruebas (servicio-gestion-despachos)`, `Pruebas (servicio-operacion-reparto)` y `Pruebas (seguridad-local)`.

La protección de ramas se configura en GitHub; añadir el workflow por sí solo no impide un merge. Se propone exigir PR, una aprobación de otro compañero, comprobaciones aprobadas y conversaciones resueltas, sin force push ni eliminación de las ramas protegidas.

El changelog se actualiza únicamente al preparar y publicar una nueva línea base en `main`, consolidando los cambios de la entrega con su versión y fecha en el PR correspondiente. Las ramas funcionales no añaden entradas por cada especificación; su avance se registra en commits y en el seguimiento SDD de Specs. Crear manualmente el tag sobre el commit aprobado de `main`. La referencia de `0.1.0` está identificada en el changelog; no se ha creado su tag automáticamente.

Los requisitos, contratos de integración y modelo de datos se mantienen en [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs). Las comprobaciones históricas de las guías retiradas pueden consultarse en Git; no acreditan esta reorganización ni sustituyen su validación manual.
