# Seguridad temporal para integración local

## Aviso para el equipo y los módulos consumidores

**Este emisor es una herramienta temporal de pruebas de G2. No es el backend de Seguridad de G7 ni acredita una integración con él.**

El kit y el OpenAPI de Seguridad compartidos definen RS256 y los tipos de token, pero no acreditan un entorno operativo con clientes y cuentas provisionados. La herramienta permite avanzar mientras se obtiene ese entorno.

No es obligatorio que Seguridad esté en la nube: un backend local accesible también permite la integración real. Se necesitan descubrimiento, JWKS, emisión de tokens y credenciales o cuentas utilizables.

El [contrato de integración de Despacho](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md) sigue siendo la referencia de rutas y permisos del negocio. Los endpoints auxiliares de este documento pertenecen exclusivamente a la herramienta local.

## Alcance y evidencia

| Comprobación | Con el emisor temporal | Con Seguridad real |
|---|---|---|
| Consumidor → Gateway → microservicio | Se puede probar con endpoints implementados | Debe repetirse |
| Firma, emisor, vencimiento y audiencia | Se ejercitan con claves y tokens locales | Pendiente con claves reales |
| Scopes y roles implementados | Se ejercitan con perfiles de prueba | Pendiente con concesiones reales |
| CORS desde frontends y Swagger | Se puede probar localmente | Revisar los orígenes del despliegue |
| Cuentas, login, contraseñas, MFA y refresco de G7 | No se implementan | Pendiente |
| Perfil activo del repartidor y propiedad de recursos | Depende de cada caso de uso | No lo resuelve el emisor |

La evidencia debe describirse como **“integración local con emisor temporal de G2”**, conservando **“integración con Seguridad real pendiente”**. En esta entrega no se han compilado ni ejecutado aplicaciones, contenedores o pruebas.

Actualmente existe la cotización simulada de Gestión. Operación todavía no expone controladores de negocio. El emisor no crea endpoints, cuentas ni datos de negocio por sí mismo.

## Activación explícita

El archivo base `compose.yaml` utiliza Seguridad real. Si falta `SEGURIDAD_URL`, las aplicaciones deben fallar al iniciar; no se omite la autenticación. Una caída de Seguridad no activa el emisor temporal.

Cada integrante utiliza su propio entorno local. Necesita el código que contiene estos cambios, Docker Desktop iniciado con contenedores Linux y los puertos configurados disponibles. No necesita instalar Java ni Maven en su equipo para este arranque con Docker.

Desde la raíz del backend, crear el archivo local de configuración solo si todavía no existe:

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
```

Para construir e iniciar los cuatro contenedores en segundo plano:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml up --build -d
```

La primera construcción descarga imágenes y dependencias. Para comprobar después el estado de los contenedores:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml ps
```

`SEGURIDAD_URL` puede quedar vacía en este modo: el archivo adicional fija explícitamente `http://seguridad-local:8090`, algoritmo RS256 y audiencia `api-despacho` en las tres aplicaciones, aunque el `.env` contenga una URL real. Cada integrante conserva su propio `.env`, ignorado por Git.

Compose añade el cuarto contenedor `seguridad-local`, y los consumidores esperan a que esté saludable. Los puertos publicados se limitan a `127.0.0.1`.

| Servicio | Dirección predeterminada desde tu equipo |
|---|---|
| Gateway | `http://localhost:8080` |
| Swagger de Gestión | `http://localhost:8081/swagger-ui.html` |
| Swagger de Operación | `http://localhost:8082/swagger-ui.html` |
| Emisor temporal | `http://localhost:8090` |

`SEGURIDAD_LOCAL_PORT` cambia el puerto publicado del emisor; internamente permanece en 8090. Los demás puertos se configuran mediante las variables habituales de `.env`.

El perfil `integracion-local` de los microservicios controla la ausencia de PostgreSQL y la cotización simulada. La elección del emisor JWT es independiente: cambiar a Seguridad real no convierte la cotización en una operación real.

## Obtener un token de servicio

El emisor dispone de estos clientes ficticios:

| Cliente local | Scopes permitidos en la herramienta |
|---|---|
| `modulo-marketplace` | `cotizaciones:calcular`, `seguimientos:leer` |
| `modulo-chatbot` | `cotizaciones:calcular`, `seguimientos:leer` |
| `modulo-ventas` | `despachos:crear`, `despachos:cancelar`, `seguimientos:leer` |

**Estas listas no acreditan altas ni concesiones en G7.** El secreto público local es `despacho-local-no-produccion`, no una credencial real. La herramienta rechaza scopes no asignados al cliente.

Desde PowerShell:

```powershell
$respuesta = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8090/api/v1/auth/token' `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    grant_type = 'client_credentials'
    client_id = 'modulo-marketplace'
    client_secret = 'despacho-local-no-produccion'
    scope = 'cotizaciones:calcular'
  }
$token = $respuesta.accessToken
```

La respuesta contiene `accessToken`, `tokenType`, `expiresIn`, `scopes` y `entorno=simulado-local`. El JWT lleva `tipo=servicio`, audiencia `api-despacho`, scopes separados por espacios y vigencia de una hora.

Para verificar el recorrido por el Gateway:

```powershell
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/cotizaciones' `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json' `
  -Body '{"destino":{"distrito":"San Borja"}}'
```

También se puede copiar `$token` en `Authorize` de Swagger de Gestión. La solicitud de negocio debe dirigirse al puerto 8080 del Gateway. Se espera `200` y cobertura disponible; sin productos no devuelve costo ni plazo.

## Tokens de usuarios ficticios

No hay login. El endpoint auxiliar permite obtener tokens de perfiles conocidos:

```powershell
$usuario = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8090/pruebas/token-usuario' `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ perfil = 'gestor' }
$tokenUsuario = $usuario.accessToken
```

| Perfil | `sub` ficticio | Roles |
|---|---|---|
| `gestor` | `11111111-1111-4111-8111-111111111111` | `GESTOR_DESPACHO` |
| `repartidor` | `22222222-2222-4222-8222-222222222222` | Lista vacía |
| `sin-rol` | `33333333-3333-4333-8333-333333333333` | Lista vacía |

Los JWT humanos llevan `tipo=acceso`, `roles` como lista, `iat`, `exp` y `jti`; duran quince minutos. No tienen audiencia de servicio ni token de refresco.

No existe un rol global `REPARTIDOR`. Ese token no crea un vínculo local ni autoriza recursos ajenos. Cada implementación deberá localizar mediante `sub` el perfil activo y comprobar la propiedad.

## Casos mínimos de prueba manual

- Salud de las cuatro aplicaciones: `UP`.
- Cotización sin token, con token alterado o vencido: `401`.
- Token de servicio con `cotizaciones:calcular`: `200` en cotización simulada.
- Token válido emitido únicamente con `seguimientos:leer`, utilizado en cotización: `403`.
- Token humano utilizado en cotización de servicio: `403`.
- Rutas administrativas implementadas: acceso según el rol.
- Operaciones de campo: validar vínculo, estado y propiedad cuando existan.
- Swagger y frontends: comprobar CORS desde los orígenes configurados.

Para probar el scope insuficiente, repetir la emisión de Marketplace solicitando solo `seguimientos:leer` y utilizar ese token en cotización.

Estas comprobaciones no certifican la integración de todos los módulos ni sustituyen las pruebas automatizadas. Cada integrante puede implementar endpoints con este entorno; debe respetar los contratos y controles de negocio.

## Claves e identificación

- Se generan claves RSA de 2048 bits en memoria en cada arranque; no se guardan en el repositorio ni en archivos.
- Solo se publica la clave pública como JWKS.
- El emisor canónico es `http://seguridad-local:8090/api/v1/auth`, interno a la red Docker.
- Los JWT y respuestas de emisión incluyen `entorno=simulado-local`.
- Las respuestas incluyen `X-Entorno-Seguridad: simulado-local`.
- La herramienta exige `HABILITAR_SEGURIDAD_LOCAL=true`; su Dockerfile no la habilita por defecto.
- Los endpoints auxiliares no se publican mediante el Gateway.

Al recrear el emisor, obtener tokens nuevos. Para una prueba con cachés nuevas, detener y recrear todo el entorno local. Los otros módulos pueden obtener tokens para llamar al Gateway de este stack; no son credenciales válidas para otros entornos. El JWKS interno no es una URL compartida en Internet.

## Registros y apagado

Utilizar los mismos archivos Compose para operar el entorno temporal. Para seguir los registros de los cuatro servicios:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml logs -f
```

Este comando solo muestra registros: no construye imágenes ni inicia contenedores. `Ctrl+C` deja de seguir los registros; los servicios iniciados con `up --build -d` siguen en ejecución. Si se modifica código Java, volver a ejecutar el comando de arranque con `up --build -d` para reconstruirlo; no hay recarga automática.

Para detener el entorno y retirar sus contenedores:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml down
```

No cambiar de emisor manteniendo las aplicaciones en ejecución y sus tokens anteriores.

## Transición a Seguridad real

Mantener este modo solo mientras no se disponga de un entorno operativo de Seguridad. Confirmar un despliegue por sí solo no cierra la integración: también deben funcionar el descubrimiento, las claves, las cuentas o clientes y sus permisos. No es necesario borrar código para cambiar de emisor; basta con detener el entorno temporal y dejar de seleccionar su archivo Compose. La retirada de la herramienta del repositorio puede hacerse después en otro PR.

1. Obtener una URL operativa, descubrimiento y JWKS accesibles, cuentas o clientes reales y scopes concedidos.
2. Detener el entorno temporal con el comando anterior, incluyendo su emisor.
3. Completar `SEGURIDAD_URL` en `.env`, sin `/api/v1/auth`. RS256 está definido en el kit compartido; comprobarlo en el entorno operativo.
4. Iniciar solo con `docker compose up --build -d`.
5. Obtener tokens reales y repetir las pruebas de firma, emisor, vencimiento, audiencia, scopes, roles y propiedad.
6. Completar la revisión del perfil estricto de G7: `iat`, `jti`, UUID del usuario y estructura de `roles`. La primera implementación todavía no cubre todos esos requisitos.
7. Registrar resultados y limitaciones antes de declarar completada la integración.

Una URL vacía en modo real provoca un error de configuración. Si Seguridad real no responde, falla la autenticación de la solicitud protegida; nunca se cambia automáticamente al emisor de prueba.

## Pruebas de la herramienta

Se escribieron pruebas HTTP que reconstruyen la clave pública desde JWKS y verifican la firma de manera independiente. Incluyen contenido alterado, scopes no concedidos, credenciales incorrectas, perfiles humanos, vigencias, límites del formulario y activación explícita.

Todavía no se han compilado ni ejecutado. Para ejecutarlas posteriormente de forma aislada:

```powershell
docker build --target pruebas -t g2-seguridad-local-pruebas ./herramientas/seguridad-local
```

El objetivo normal de la imagen no ejecuta esa etapa. Las pruebas del Gateway y microservicios se mantienen por separado.

La herramienta utiliza únicamente JDK 21 para HTTP y firma [cite: 1, 2]. No incorpora una base de identidades ni forma parte de los servicios de negocio.

## Referencias

1. [Servidor HTTP del JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html).
2. [Firma y verificación con Java Signature](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html).
3. [Combinación de archivos Compose](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/).
