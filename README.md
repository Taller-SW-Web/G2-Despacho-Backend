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
| Gestión | [V1 original](servicio-gestion-despachos/src/main/resources/db/migration/V1__esquema_original_gestion.sql) y [V2 acumulada](servicio-gestion-despachos/src/main/resources/db/migration/V2__actualizacion_esquema_gestion.sql) | 17 tablas de negocio, parámetros, motivos de fallo y 50 distritos de Lima/Callao. |
| Operación | V1 a V5 existentes en `servicio-operacion-reparto/src/main/resources/db/migration` | Tablas, funciones y parámetros de perfiles, flota, jornadas, reservas, proyecciones y evidencias; sin datos de negocio de prueba. |

Flyway queda habilitado para ambas bases locales, con esquema `public`, baseline automática desactivada y limpieza de Flyway desactivada. Hibernate mantiene `ddl-auto=none`. Las modificaciones futuras de Gestión se agregan desde V3; no editar archivos que ya se hayan aplicado a una base persistente.

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

Se esperan versiones 1 y 2 con `success=true`; Operación debe registrar 1 a 5 en su propia base. Ambas historias se comprobaron en el entorno local el 23/10/2026. Esto confirma las migraciones locales, no una sincronización automática con Supabase.

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

Es una herramienta de G2, no Seguridad real de G7. Mantiene validación JWT RS256, permisos y controles de negocio; no implementa login ni cuentas reales. No utilizar el emisor temporal para un despliegue público.

| Perfil de prueba | Identificador | Uso |
|---|---|---|
| `gestor` | `11111111-1111-4111-8111-111111111111` | Rol `GESTOR_DESPACHO`. |
| `repartidor` | `22222222-2222-4222-8222-222222222222` | Requiere perfil local activo, vínculo con ese usuario y propiedad del recurso. |
| `sin-rol` | `33333333-3333-4333-8333-333333333333` | Comprobaciones de denegación de permisos. |

Los tokens de usuario duran 15 minutos. Las claves RSA se generan en memoria al arrancar; al reiniciar Seguridad, obtener tokens nuevos y reiniciar los consumidores si conservan las claves en caché. El emisor interno es `http://seguridad-local:8090/api/v1/auth`.

Para un token de servicio de cotización:

```powershell
$tokenServicio = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/api/v1/auth/token' -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type = 'client_credentials'; client_id = 'modulo-marketplace'; client_secret = 'despacho-local-no-produccion'; scope = 'cotizaciones:calcular' }).accessToken
```

El secreto es ficticio y solo pertenece al emisor local. Los clientes de prueba incluyen `modulo-marketplace`, `modulo-chatbot`, `modulo-ventas` y `modulo-despacho`. Gestión tiene configurado el cliente técnico para consultar incidencias y reservar/liberar capacidad y publicar proyecciones en Operación.

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
3. En **Repartidores**, registrar un perfil de prueba. Desde **Más acciones > Vincular cuenta**, asociar el usuario temporal `22222222-2222-4222-8222-222222222222`. Ese identificador solo puede vincularse a un repartidor; representa el único perfil repartidor del emisor temporal.
4. En **Vehículos**, registrar una furgoneta disponible con capacidades positivas. Por ejemplo: 100 kg, 1 m³ y 10 paquetes.
5. En **Monitoreo de flota**, pulsar **Abrir jornada**, seleccionar el repartidor vinculado, el vehículo, la zona y la fecha de hoy. Estar vinculado por sí solo no lo hace disponible para asignación.
6. En **Programación**, usar **Generar pedido de prueba** y asignarlo a un candidato con jornada activa y capacidad suficiente. Los pedidos simulados se guardan en PostgreSQL local y comienzan pendientes de asignación.
7. En **Entregas fallidas**, consultar los fallos existentes y abrir **Ver detalle**. El botón de Programación no genera fallos: para comprobar listado y detalle hacen falta incidencias de prueba con historial y referencias coherentes de repartidor y jornada. Una base nueva muestra el listado vacío.

El registro funcional de fallos de F-03 todavía no está integrado. En desarrollo, el frontend del repartidor utiliza una simulación en memoria; sus cambios no aparecen en el administrador ni en PostgreSQL. Para abrir esa aplicación por separado, ejecutar `npm run dev:repartidor` en otra terminal del frontend; su puerto es 5174.

En la comprobación local se preparó manualmente un fallo por `CLIENTE_AUSENTE`, con un intento consumido y retorno pendiente; se verificaron listado, detalle, historial y sincronización con Operación. Esa preparación de datos no forma parte del arranque automático ni implementa F-03. Las fotografías necesitan Storage y una evidencia asociada. Gestión implementa recepción y reprogramación (ES-F04-03/04); el cierre ES-F04-05 mantiene su alcance pendiente.

Los registros creados o preparados durante las pruebas permanecen en el volumen local de Docker. No se incluyen en los commits ni se copian a otros equipos: una instalación nueva parte de las migraciones y sus catálogos. `docker compose down -v` elimina también esos registros.

## Integraciones pendientes y frontends

- Productos y Ventas no se levantan en este Compose. La cotización utiliza el catálogo de productos de prueba configurado en Gestión. La recepción real de pedidos requiere Productos y sus credenciales; las notificaciones a Ventas pueden quedar pendientes hasta configurar el destino y la identidad técnica.
- Las fotografías requieren un bucket privado y `EVIDENCIAS_STORAGE_URL`, `EVIDENCIAS_BUCKET` y `EVIDENCIAS_CLAVE_SERVICIO` en Operación. Vacíos, esos recorridos no están disponibles; no se sustituye Storage por un servicio local.
- Los frontends pueden usar `VITE_API_BASE_URL=http://localhost:8080` y un token temporal mediante su configuración de desarrollo. CORS admite los puertos 5173 y 5174. Si el frontend necesita refrescar tokens, debe usar la configuración vigente de su propio repositorio.
- La información de errores incorpora un identificador de correlación; buscarlo en `docker compose logs` para seguir una petición entre servicios.

## Despliegues existentes

Los Dockerfiles y perfiles `application-render.yaml` de los tres backends se conservan. El único Compose está destinado al entorno local y no modifica Render ni Supabase.

Las plantillas de despliegue están ahora dentro de sus aplicaciones: [Gateway](api-gateway/.env.example), [Gestión](servicio-gestion-despachos/.env.example) y [Operación](servicio-operacion-reparto/.env.example). Configurar las variables en el panel de cada servicio. Cada microservicio utiliza su propia conexión de base de datos; el Gateway recibe las URLs de los servicios y no necesita credenciales PostgreSQL.

El perfil `render` de ambos servicios deja Flyway desactivado por defecto, pero la variable `SPRING_FLYWAY_ENABLED` del despliegue puede sobrescribir ese valor. Comprobar las variables efectivas antes de desplegar esta historia V1/V2 sobre un Supabase existente. Adoptar Flyway allí requiere comparar el esquema y conciliar el historial; una posible baseline 2 de Gestión es una tarea posterior. Hibernate mantiene `ddl-auto=none`.

Los scripts heredados F-03/F-05/F-06 de Operación se conservaron en `servicio-operacion-reparto/src/test/resources/flota/compatibilidad`. Sus pruebas los cargan como recursos. No son migraciones automáticas de una base nueva ni deben volver a aplicarse a ciegas al entorno publicado. Los datos F-04 usados por una prueba están en `src/test/resources/flota/datos-demo-f04.sql` del mismo servicio.

## Pruebas y contratos

La implementación backend de ES-F04-03 y sus límites se describen en [verificación de recepción en centro](servicio-gestion-despachos/docs/ES-F04-03-verificacion.md). Esa especificación reutiliza V1/V2. ES-F04-04 añade V3 para conservar la respuesta de reprogramación junto con su historial y evento; no modifica Operación ni su base. Las verificaciones usan bases desechables. Los datos históricos sin referencias completas requieren reconciliación verificable; los comandos los rechazan sin adivinar reservas. La trazabilidad vigente de reprogramación se mantiene en [su evidencia SDD](../G2-Despacho-Specs/especificaciones/ES-F04-04-ReprogramarDespachoFallido/evidence.md).

Para el recorrido de navegador de ES-F04-04, con Docker disponible, ejecutar desde Gestión en PowerShell:

```powershell
.\mvnw.cmd test-compile dependency:build-classpath '-Dmdep.outputFile=target/classpath-revision.txt'
$classpathF04 = 'target/test-classes;target/classes;' + (Get-Content target/classpath-revision.txt -Raw).Trim()
java -cp $classpathF04 com.modulodespacho.gestiondespachos.servicio_gestion_despachos.ReprogramacionNavegadorPruebas
```

El arranque crea PostgreSQL desechable, aplica Flyway y reutiliza el escenario sintético de recepción/reprogramación con reloj `2026-10-25T03:30Z` (24/10 en Lima). Expone Gestión en `18085` con JWT controlado; no incorpora esa identidad al artefacto productivo. Tras `REVISION_F04_LISTA`, ejecutar `npm run test:f04:e2e` desde frontend. Para repetir el recorrido hay que reiniciar esta instancia, porque la prueba acepta la decisión real. Se cierra con Ctrl+C, al crear `target/detener-revision-f04`, o después de 15 minutos. Antes de otro arranque, retirar únicamente ese archivo de señal si existe. Gateway, Seguridad, Operación y Ventas reales no quedan acreditados por este recorrido.

Gestión ejecuta su procesador de liberaciones con `RECEPCION_PROCESADOR_HABILITADO=true`, revisión de pendientes cada `RECEPCION_INTERVALO_PROCESADOR_MS=1000` y calendario `RECEPCION_ESPERAS=PT30S,PT2M,PT10M,PT30M,PT1H` (duraciones ISO 8601). Necesita la URL de Operación y la credencial técnica de `modulo-despacho` autorizada con `operacion:capacidad:liberar`; reutiliza `OPERACION_REPARTO_URL`, `OPERACION_CLIENT_ID`, `OPERACION_CLIENT_SECRET` o `OPERACION_TOKEN_SERVICIO`. Los secretos se configuran fuera de Git. Sin confirmación válida, conserva el retorno físico y la liberación pendiente; los errores de autorización o datos se señalan para revisión y se reintentan cada hora. Desactivar el procesador conserva las solicitudes, pero no las entrega hasta reactivarlo.

Cada backend tiene su propio Maven Wrapper y sus pruebas. Para ejecutarlas manualmente, desde el directorio correspondiente:

```powershell
.\mvnw.cmd clean test
```

Para ejecutar Maven Wrapper fuera de Docker se necesita Java 21. Las suites de integración requieren Docker y crean bases aisladas con Testcontainers. Gestión necesita la imagen PostGIS definida en su configuración de pruebas. Nunca apuntar esas suites a Supabase ni a una base compartida, porque algunas crean, modifican o limpian tablas. `clean` evita conservar migraciones antiguas en `target/classes`.

Los Dockerfiles omiten las pruebas durante la construcción: `docker compose up --build` no ejecuta estas suites. El recorrido manual registrado arriba tampoco sustituye sus resultados.

Las pruebas de Seguridad temporal permanecen en `seguridad-local/pruebas`. Se retiraron los Compose y verificadores específicos de las demos; los datos y adaptaciones usados por pruebas se conservaron dentro de Operación.

## Integración continua

[CI backend](.github/workflows/ci.yaml) se ejecuta en los PR dirigidos a `develop` o `main`, en los cambios integrados a esas ramas y bajo ejecución manual desde Actions. Usa Java 21 y comprueba los tres servicios por separado con Maven Wrapper y `clean verify`. Las pruebas existentes de integración crean PostgreSQL/PostGIS temporal mediante Testcontainers; el workflow no carga `.env`, no utiliza Supabase y no inicia el Compose de desarrollo. Los informes de Maven se conservan siete días en los artefactos de cada ejecución.

Una cuarta comprobación compila y ejecuta las pruebas propias de `seguridad-local`, que levantan su emisor aislado con puerto efímero. El workflow no despliega servicios, no publica releases y no crea tags.

Para exigir el resultado antes de integrar, configurar la protección de `develop` y `main` en GitHub y seleccionar estas cuatro comprobaciones cuando hayan aparecido en una ejecución:

- `Pruebas (api-gateway)`
- `Pruebas (servicio-gestion-despachos)`
- `Pruebas (servicio-operacion-reparto)`
- `Pruebas (seguridad-local)`

La protección de ramas se configura en GitHub; añadir el workflow por sí solo no impide un merge. Se propone exigir PR, una aprobación de otro compañero, comprobaciones aprobadas y conversaciones resueltas, sin force push ni eliminación de las ramas protegidas.

El changelog se prepara en `develop` y llega a `main` en el PR de entrega. Mantener los cambios aún no publicados en `Sin publicar`, cerrar el corte aproximadamente cada dos semanas con su versión y fecha, y crear manualmente el tag sobre el commit aprobado de `main`. No es necesario adoptar Scrum para usar esta cadencia. La referencia de `0.1.0` está identificada en el changelog; no se ha creado su tag automáticamente.

Los requisitos, contratos de integración y modelo de datos se mantienen en [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs). Las comprobaciones históricas de las guías retiradas pueden consultarse en Git; no acreditan esta reorganización ni sustituyen su validación manual.
