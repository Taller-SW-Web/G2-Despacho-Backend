# Entorno local con Docker para pruebas y depuración

`compose.local.yaml` levanta el backend completo en tu equipo, sin Supabase, Render ni Seguridad real:

| Contenedor | Qué hace | Desde tu equipo |
|---|---|---|
| `postgres` | PostgreSQL 17 con las bases `despacho_gestion` y `despacho_operacion` | `localhost:15432` |
| `seguridad-local` | Emisor temporal de JWT de G2 ([detalle](seguridad-temporal-local.md)) | `http://localhost:8090` |
| `servicio-gestion-despachos` | F-01, F-02, F-04 | `http://localhost:8081/swagger-ui.html` · depuración `5005` |
| `servicio-operacion-reparto` | F-03, F-05, F-06 | `http://localhost:8082/swagger-ui.html` · depuración `5006` |
| `api-gateway` | Punto de entrada de los frontends | `http://localhost:8080` · depuración `5007` |
| `adminer` (opcional) | Cliente web de base de datos | `http://localhost:8088` |

Todos los puertos se publican solo en `127.0.0.1`. Las credenciales son ficticias y exclusivamente locales.

> La evidencia obtenida aquí es **integración local con emisor temporal de G2**; la integración con Seguridad real y Ventas sigue pendiente.

## Requisitos

- Docker Desktop iniciado con contenedores Linux. No se necesita Java ni Maven en el equipo.
- Puertos libres: 8080–8082, 8090, 15432 y 5005–5007. Si alguno está ocupado, ver [Cambiar puertos](#cambiar-puertos).
- Si ya tienes levantado `compose.yaml` u otra demo, detenla antes: comparten puertos.

## Arrancar, revisar y detener

Desde la raíz del backend:

```powershell
docker compose -f compose.local.yaml up --build -d      # la primera vez descarga imágenes y dependencias
docker compose -f compose.local.yaml ps                  # todos deben aparecer como (healthy)
docker compose -f compose.local.yaml logs -f servicio-gestion-despachos
docker compose -f compose.local.yaml stop                # detiene y conserva los datos
docker compose -f compose.local.yaml down                # elimina contenedores y conserva los datos
docker compose -f compose.local.yaml down -v             # elimina también la base: el próximo arranque parte de cero
```

Tras cambiar código Java, reconstruir solo ese servicio:

```powershell
docker compose -f compose.local.yaml up --build -d servicio-operacion-reparto
```

## Base de datos

En el primer arranque (volumen vacío), [`herramientas/entorno-local/postgres/10-crear-bases.sh`](../herramientas/entorno-local/postgres/10-crear-bases.sh) crea una base por microservicio y aplica las migraciones de `src/main/resources/db/migration` en orden de versión. Gestión arranca con los distritos de Lima y Callao (V4); el resto de tablas queda vacío.

Flyway queda desactivado, igual que en Render/Supabase. Motivo: Gestión tiene dos migraciones `V3` (`V3__programacion_despachos.sql` y `V3__zonas_codigos_postales.sql`) y el equipo decidió no renumerarlas hasta conciliar el historial Flyway de cada entorno ([f06](f06-jornadas-capacidad.md), [f04](f04-detalle-incidencia.md)). Con Flyway activo, una base nueva no arrancaría.

**Si agregas o modificas una migración**, recrea la base para aplicarla: `docker compose -f compose.local.yaml down -v` y vuelve a levantar.

Conexión con cualquier cliente (DBeaver, IntelliJ, `psql`):

| Campo | Valor |
|---|---|
| Host / puerto | `localhost` / `15432` |
| Usuario / clave | `despacho_local` / `despacho-local-no-produccion` |
| Bases | `despacho_gestion`, `despacho_operacion` |

O desde el contenedor: `docker compose -f compose.local.yaml exec postgres psql -U despacho_local -d despacho_gestion`.

Con navegador: `docker compose -f compose.local.yaml --profile herramientas up -d` y abrir `http://localhost:8088` (sistema PostgreSQL, servidor `postgres`).

## Tokens para probar

No hay login. El emisor entrega tokens de perfiles ficticios (duran 15 minutos):

```powershell
$token = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/pruebas/token-usuario' `
  -ContentType 'application/x-www-form-urlencoded' -Body @{ perfil = 'gestor' }).accessToken
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/zonas' -Headers @{ Authorization = "Bearer $token" }
```

| Perfil | Uso |
|---|---|
| `gestor` | Rol `GESTOR_DESPACHO`: pantallas administrativas (zonas, programación, flota, jornadas, entregas fallidas) |
| `repartidor` | Sin rol global. Para `/api/v1/repartidor/**`, primero registra un repartidor y vincúlalo al `sub` `22222222-2222-4222-8222-222222222222` |
| `sin-rol` | Comprobar respuestas 403 |

Los tokens de servicio (`client_credentials`) y los clientes ficticios están en [seguridad-temporal-local.md](seguridad-temporal-local.md). En este entorno, `modulo-despacho` también recibe los scopes de capacidad que Gestión necesita para llamar a Operación al asignar despachos.

## Frontends contra este backend

Ejecutar los frontends en el equipo, sin Docker, para conservar la recarga en caliente:

```powershell
# En G2-Despacho-Frontend/apps/despacho-administrativo/.env (o despacho-repartidor/.env)
VITE_API_BASE_URL=http://localhost:8080
VITE_DEV_TOKEN=<token del perfil gestor>

npm run dev:administrativo     # http://localhost:5173
npm run dev:repartidor         # http://localhost:5174
```

El Gateway ya admite CORS desde 5173 y 5174. El probador F-01 de la app administrativa obtiene tokens del emisor en `localhost:8090` mediante el proxy de Vite.

## Depuración remota (breakpoints)

Cada JVM expone JDWP. Basta con conectar el IDE al puerto del servicio:

**IntelliJ IDEA:** Run → Edit Configurations → `+` → *Remote JVM Debug* → Host `localhost`, Port `5005` (Gestión), `5006` (Operación) o `5007` (Gateway) → Debug. Elegir como classpath el módulo correspondiente.

**VS Code** (extensión *Debugger for Java*), en `.vscode/launch.json`:

```json
{
  "version": "0.2.0",
  "configurations": [
    { "type": "java", "name": "Gestión (Docker)", "request": "attach", "hostName": "localhost", "port": 5005, "projectName": "servicio-gestion-despachos" },
    { "type": "java", "name": "Operación (Docker)", "request": "attach", "hostName": "localhost", "port": 5006, "projectName": "servicio-operacion-reparto" },
    { "type": "java", "name": "Gateway (Docker)", "request": "attach", "hostName": "localhost", "port": 5007, "projectName": "api-gateway" }
  ]
}
```

Para depurar el **arranque** (configuración, beans), levantar con `JAVA_DEBUG_SUSPEND=y`: la JVM espera al depurador antes de iniciar. Mientras tanto el contenedor no queda `healthy`:

```powershell
$env:JAVA_DEBUG_SUSPEND = 'y'; docker compose -f compose.local.yaml up -d servicio-gestion-despachos; Remove-Item Env:JAVA_DEBUG_SUSPEND
```

Los errores devuelven un `correlationId`; búscalo en los registros para ubicar la petición:

```powershell
docker compose -f compose.local.yaml logs servicio-gestion-despachos | Select-String '<correlationId>'
```

## Cambiar puertos

Copiar `.env.local.example` como `.env.local` (ignorado por Git), ajustar los valores y pasar el archivo en **todos** los comandos:

```powershell
docker compose --env-file .env.local -f compose.local.yaml up --build -d
```

## Limitaciones conocidas

- **Evidencias fotográficas:** sin `EVIDENCIAS_*` no hay almacenamiento. Las autorizaciones de carga y lectura de fotos de F-03/F-04 responden como no disponibles. Para probarlas, completar esas variables con un bucket de pruebas en `.env.local`, nunca en el repositorio.
- **Ventas y Productos:** no hay servicios externos. La cotización usa el catálogo stub de productos de `application.yaml`; las notificaciones a Ventas fallan y quedan como efecto pendiente.
- **Seguridad:** el emisor no implementa cuentas, login ni refresco de G7. Ver [seguridad-temporal-local.md](seguridad-temporal-local.md).
- **Datos:** la base parte vacía salvo distritos. Los datos de `herramientas/f04/` pertenecen a la demo aislada F-04 (`compose.f04.yaml`) y no se aplican aquí.

## Verificación de este entorno

Comprobado el 17/10/2026 con Docker 29.8.1 y Compose v5.5.1, partiendo de un volumen vacío:

- Los cinco contenedores quedan `healthy`; se aplican las 11 migraciones (6 de Gestión y 5 de Operación).
- A través del Gateway con token `gestor`, responden 200: `/api/v1/distritos` (con datos), `/api/v1/zonas`, `/api/v1/despachos/pendientes`, `/api/v1/repartidores`, `/api/v1/furgonetas` y `/api/v1/flota/monitoreo`. Sin token, `/api/v1/zonas` responde 401.
- El emisor entrega a `modulo-despacho` el token de servicio con scopes de capacidad.
- Los puertos JDWP 5005, 5006 y 5007 aceptan conexión. No se probó una sesión de depuración con breakpoints desde un IDE.
- No se ejecutó el flujo completo desde los frontends ni las pruebas automatizadas del backend.
