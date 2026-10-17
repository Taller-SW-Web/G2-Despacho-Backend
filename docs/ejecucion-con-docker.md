# Ejecución local del backend con Docker

## Estado

La base Docker se preparó en `feature/ejecucion-backend-docker`. La rama `feature/seguridad-local-temporal` agrega un emisor opcional para continuar el desarrollo sin un entorno real de G7.

**Para el equipo y otros módulos:** el emisor temporal no representa Seguridad real. Consultar la [guía de seguridad temporal](seguridad-temporal-local.md) antes de probar o comunicar resultados de integración.

No se han construido imágenes, descargado imágenes, ejecutado contenedores ni ejecutado pruebas o aplicaciones por indicación del usuario. Las instrucciones de este documento están pendientes de validación en ejecución.

## Alcance

`compose.yaml` inicia las tres aplicaciones del repositorio:

| Servicio | Puerto en tu equipo, por defecto | Puerto interno |
|---|---:|---:|
| API Gateway | 8080 | 8080 |
| Gestión de Despachos | 8081 | 8081 |
| Operación de Reparto | 8082 | 8082 |

Los puertos publicados se limitan a `127.0.0.1`, para uso desde este equipo.

Los dos microservicios utilizan `integracion-local`: no crean conexiones PostgreSQL ni ejecutan Flyway. Gestión habilita la cotización simulada. Las tres aplicaciones conservan la validación JWT. El Compose base utiliza Seguridad real; `compose.seguridad-local.yaml` incorpora un emisor ficticio cuando se añade explícitamente.

Este archivo Compose sirve para la integración local inicial. Al incorporar persistencia y funcionalidades reales será necesario preparar otra configuración con las conexiones independientes de cada microservicio y sin el perfil simulado.

## Requisitos

- Docker Desktop iniciado y configurado para contenedores Linux.
- Docker Compose disponible mediante `docker compose`.
- Acceso a Internet para descargar las imágenes base, Maven y dependencias en la primera construcción.
- Para modo real: URL de Seguridad accesible desde los contenedores y tokens válidos. El kit compartido define RS256.
- Para modo temporal: activar explícitamente el archivo Compose adicional; no se necesitan cuentas ni credenciales reales.

Para este arranque con Docker no es necesario instalar Java ni Maven en el equipo: se utilizan dentro de la etapa de construcción de cada imagen.

## Archivos y configuración

- `compose.yaml`: define las tres aplicaciones, su red, variables y puertos.
- `compose.seguridad-local.yaml`: agrega y selecciona el emisor temporal para las tres aplicaciones.
- `Dockerfile` en cada aplicación: construye su JAR con Java 21 y lo ejecuta en una imagen JRE 21.
- `.dockerignore` en cada aplicación: excluye archivos locales, variables y artefactos de compilación del contexto.
- `.env.example` en la raíz: plantilla compartida en Git.
- `.env` en la raíz: valores locales, ignorados por Git.

En este checkout ya se creó un `.env` local sin URL de Seguridad. En otro clon, desde la raíz del repositorio backend, copiar la plantilla si todavía no existe:

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
```

En modo real, editar `.env` y completar `SEGURIDAD_URL` con el origen real, sin agregar `/api/v1/auth` al final. RS256 está definido en el kit compartido; falta comprobar la integración con un entorno operativo.

En modo real, una URL vacía provoca un error de configuración al iniciar las aplicaciones. La validación se realiza al crear el decodificador JWT, para que el archivo adicional pueda sustituir explícitamente la URL en modo temporal. No hay autenticación opcional ni cambio automático de emisor.

| Variable del `.env` raíz | Uso |
|---|---|
| `SEGURIDAD_URL` | Origen de Seguridad accesible desde Docker |
| `SEGURIDAD_AUDIENCIA` | Por defecto `api-despacho` |
| `SEGURIDAD_ALGORITMO` | Algoritmo asimétrico permitido; inicialmente `RS256` |
| `GATEWAY_PORT` | Puerto del Gateway en tu equipo; por defecto 8080 |
| `GESTION_DESPACHOS_PORT` | Puerto de Gestión en tu equipo; por defecto 8081 |
| `OPERACION_REPARTO_PORT` | Puerto de Operación en tu equipo; por defecto 8082 |
| `SEGURIDAD_LOCAL_PORT` | Puerto del emisor temporal cuando se activa; por defecto 8090 |
| `CORS_ORIGENES` | Orígenes de los frontends separados por comas |
| `SOLICITUD_MAXIMO_BYTES` | Tamaño máximo del cuerpo; por defecto 1048576 bytes |

Compose permite los dos frontends locales tanto desde `localhost` como desde `127.0.0.1`, además de agregar a CORS los orígenes de los dos Swagger UI usando los puertos publicados configurados. También configura el servidor público de Swagger según `GATEWAY_PORT`.

Compose lee el `.env` raíz y pasa explícitamente los valores requeridos mediante `environment`. No es necesario crear un `.env` por aplicación para este flujo [cite: 1].

Las variables ya definidas en la terminal tienen prioridad sobre `.env`. Si antes se configuró una `SEGURIDAD_URL` temporal con `$env:`, revisar que no esté sustituyendo el valor que se acaba de escribir en el archivo [cite: 1].

Los `.env.example` dentro de cada aplicación se conservan como referencia para Maven o el IDE. Compose no los utiliza. Ejecutar `mvnw.cmd` directamente sigue sin cargar automáticamente el `.env` raíz.

## CORS y llamadas de otros módulos

`CORS_ORIGENES` contiene los orígenes de páginas web autorizadas para invocar al Gateway desde el navegador: protocolo, dominio y puerto, sin rutas de API. En desarrollo Compose incluye los dos frontends con `localhost` y `127.0.0.1`; también agrega automáticamente los orígenes de Swagger UI [cite: 5].

Las llamadas habituales de un backend externo al Gateway no necesitan registrar la URL de ese backend en CORS. Por ejemplo, Ventas solicita un token de servicio a Seguridad y llama al Gateway con el scope de la operación. El Gateway y el microservicio validan el token y sus permisos. CORS no sustituye la autenticación ni decide qué módulo puede ejecutar una operación.

Si otro frontend tuviera un consumo directo autorizado por el contrato, habría que registrar el origen de ese frontend. Los tokens de servicio de las integraciones se obtienen y conservan en los backends, no mediante credenciales técnicas en el navegador.

## Seguridad en el equipo anfitrión o en otro despliegue

Si Seguridad está desplegada, utilizar su origen HTTPS real.

Si Seguridad se ejecuta en Windows, el `localhost` de un contenedor se refiere al propio contenedor. En Docker Desktop se utiliza `host.docker.internal` para acceder al equipo anfitrión [cite: 4]. Por ejemplo, adaptar en `.env` el siguiente valor al puerto real:

```dotenv
SEGURIDAD_URL=http://host.docker.internal:8090
```

El documento de descubrimiento debe publicar un `jwks_uri` que también sea accesible desde los contenedores. Cambiar únicamente `SEGURIDAD_URL` no corrige una dirección de claves publicada como `localhost`. El `issuer` debe conservarse como lo publique Seguridad, ya que debe coincidir con el `iss` del token.

## Iniciar las tres aplicaciones

Para iniciar el entorno temporal sin Seguridad real, abrir una terminal en la raíz de `G2-Despacho-Backend`:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml up --build -d
```

Esto agrega un cuarto contenedor de herramientas y mantiene los JWT verificados. Obtener tokens y operar ese entorno siguiendo la [guía temporal](seguridad-temporal-local.md). Los comandos restantes de esta guía usan el archivo base; al trabajar con el emisor temporal, añadir ambos argumentos `-f` también en `ps`, `logs` y `down`.

Para iniciar las tres aplicaciones con Seguridad real, después de configurar `.env`:

```powershell
docker compose up --build
```

Este comando construye las tres imágenes y muestra sus registros. La primera construcción puede tardar por las descargas. Las siguientes reutilizan la caché de Docker y de dependencias Maven.

Para iniciarlas en segundo plano:

```powershell
docker compose up --build -d
docker compose ps
```

Cada Dockerfile define un chequeo de `/actuator/health`. Compose espera que ambos microservicios estén saludables antes de iniciar el Gateway [cite: 2]. La salud no comprueba todavía la disponibilidad de Seguridad: el descubrimiento JWT se realiza al recibir un token.

Dentro de la red de Compose, el Gateway utiliza `http://servicio-gestion-despachos:8081` y `http://servicio-operacion-reparto:8082`. Esas direcciones son para los contenedores; el navegador utiliza `http://localhost:8080`.

## Comprobar y utilizar la integración

Con los puertos predeterminados, consultar:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health'
Invoke-RestMethod -Uri 'http://localhost:8081/actuator/health'
Invoke-RestMethod -Uri 'http://localhost:8082/actuator/health'
```

Los tres deberían responder con `status=UP`. Si se cambiaron los puertos en `.env`, utilizar los valores elegidos.

La documentación estará en:

- Gestión: `http://localhost:8081/swagger-ui.html`.
- Operación: `http://localhost:8082/swagger-ui.html`.

Desde Swagger de Gestión, ingresar en `Authorize` un token de servicio obtenido de Seguridad con el scope de cotización y probar el recorrido por el Gateway, siguiendo la [guía de configuración inicial](configuracion-inicial-api-gateway.md). Se conserva el [contrato oficial](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).

La cotización continúa siendo simulada: solo San Borja tiene cobertura, el catálogo contiene dos SKU y el costo es de prueba. Operación todavía no tiene controladores de negocio.

Para la primera verificación manual, seguir esta secuencia:

1. Comprobar que `docker compose ps` muestre los tres servicios como saludables.
2. Comprobar las tres respuestas de salud indicadas anteriormente.
3. Sin token, enviar `POST` al Gateway y comprobar que responda `401`, por ejemplo desde PowerShell:

   ```powershell
   curl.exe -i -X POST http://localhost:8080/api/v1/cotizaciones
   ```

4. Obtener de Seguridad un token de servicio con `tipo=servicio`, audiencia `api-despacho` y scope `cotizaciones:calcular`.
5. Abrir Swagger de Gestión, ingresar ese token en `Authorize` y ejecutar la cotización con un destino en San Borja, siguiendo el ejemplo de la guía inicial.
6. Confirmar que la URL de la solicitud sea `http://localhost:8080/api/v1/cotizaciones` y que responda `200` con cobertura disponible. Una solicitud sin productos no devuelve costo ni plazo.

El último paso verifica el recorrido real navegador → Gateway → Gestión con autenticación. Salud por sí sola no comprueba enrutamiento, CORS ni validación de tokens. Operación solo puede comprobarse en salud hasta que se implementen sus endpoints de negocio.

## Registros, reconstrucción y apagado

Los comandos sin `-f` de esta sección corresponden al modo con Seguridad real. En modo temporal, agregar ambos archivos como se muestra en la [guía de seguridad temporal](seguridad-temporal-local.md#registros-y-apagado). `logs -f` solo consulta registros; el arranque se realiza con `up --build`.

Ver registros de los tres servicios:

```powershell
docker compose logs -f
```

Ver únicamente los del Gateway:

```powershell
docker compose logs -f api-gateway
```

Después de modificar código Java, reconstruir y reiniciar los servicios afectados:

```powershell
docker compose up --build -d
```

Esta configuración ejecuta los JAR construidos; no monta el código ni recarga cambios automáticamente.

Para detener y retirar los contenedores y la red del proyecto:

```powershell
docker compose down
```

## Pruebas y construcción

Los Dockerfiles utilizan `-Dmaven.test.skip=true` para construir las imágenes sin compilar ni ejecutar pruebas. No requieren un servidor de Seguridad ni PostgreSQL durante la construcción.

Construir las imágenes y arrancar los contenedores no sustituye las pruebas automatizadas. Las pruebas se ejecutan por separado, desde cada aplicación:

```powershell
.\mvnw.cmd test
```

Las pruebas actuales tienen su propia configuración y no necesitan el `.env` raíz ni credenciales reales de Seguridad. Las pruebas existentes con Testcontainers requieren Docker iniciado; si se ejecutan desde Windows con Maven, también requieren JDK 21.

## Comprobaciones pendientes

- Resolver y validar la configuración de Compose con la URL real.
- Construir las tres imágenes.
- Verificar los tres chequeos de salud y los registros de arranque.
- Verificar el descubrimiento, JWKS y tokens del emisor real desde Docker.
- Probar Swagger y la cotización pasando por el Gateway.
- Ejecutar las pruebas automatizadas por aplicación.

## Referencias

1. [Variables e interpolación de Compose](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).
2. [Orden de inicio y servicios saludables](https://docs.docker.com/compose/how-tos/startup-order/).
3. [Guía oficial de Java y Docker](https://docs.docker.com/guides/java/).
4. [Acceso al equipo anfitrión y resolución de host.docker.internal](https://docs.docker.com/reference/cli/docker/container/run/#add-entries-to-container-hosts-file---add-host).
5. [CORS y peticiones desde navegadores](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS).
