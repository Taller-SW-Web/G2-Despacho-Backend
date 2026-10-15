# Configuración inicial del API Gateway

## Estado de esta entrega

Código preparado en la rama `feature/configuracion-inicial-api-gateway`. Por indicación del usuario, no se han ejecutado aplicaciones, Maven, compilaciones ni pruebas. Esta entrega no acredita todavía que la integración funcione en ejecución.

El contrato oficial sigue siendo [api-contract.md](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md). Este documento describe configuración y uso; no reemplaza ni duplica los payloads contractuales.

**Estado de Seguridad:** existe una [herramienta temporal de emisión JWT](seguridad-temporal-local.md) para desarrollo local. No representa el servicio real de G7. Sus tokens y claves permiten ejercitar la implementación de G2; la integración con el emisor, cuentas y clientes reales sigue pendiente.

## Qué contiene

- Gateway en el puerto local 8080 y microservicios en 8081 y 8082.
- Enrutamiento conservando método, ruta, query, cuerpo, `Authorization` e `Idempotency-Key`.
- CORS centralizado en el Gateway para orígenes configurados.
- Validación de firma, algoritmo permitido, emisor, vigencia, `sub` y `tipo` en las tres aplicaciones.
- Audiencia `api-despacho` obligatoria en tokens de servicio; no se exige en tokens humanos.
- Autorización de integraciones mediante scopes y de operaciones administrativas mediante `roles`.
- Generación o propagación de `X-Correlation-Id`, también en errores de autenticación.
- Errores del Gateway y de la cotización simulada en `application/problem+json`.
- Límite de cuerpo de 1 MiB por solicitud en el Gateway, incluidos cuerpos sin `Content-Length`.
- Tiempos máximos de conexión y respuesta hacia los microservicios; problemas de conexión o espera se traducen a 503.
- Salud e información básicas. Las rutas internas y el resto de Actuator no se publican.
- Swagger UI en ambos microservicios, deshabilitado por defecto y habilitado en el perfil local.
- Un flujo de cotización simulado disponible únicamente con `integracion-local`.

El Gateway realiza la validación inicial del token. Los microservicios vuelven a validarlo y aplican los permisos de cada operación; no confían en encabezados de identidad aportados por el consumidor. El Gateway no emite tokens y no necesita un `client_secret` para validar firmas.

## Variables de entorno

Cada aplicación tiene un `.env.example`. Son plantillas: Spring Boot no carga automáticamente un archivo `.env`; hay que trasladar sus valores a las variables del proceso, al IDE o a la plataforma de despliegue.

Para iniciar las tres aplicaciones con Docker se utiliza un `.env` en la raíz del backend, leído por Compose. Ese flujo no necesita cargar manualmente las variables en cada terminal; consultar la [guía de ejecución con Docker](ejecucion-con-docker.md).

| Variable | Aplicaciones | Uso |
|---|---|---|
| `SEGURIDAD_URL` | Las tres | Obligatoria. Origen de Seguridad, sin `/api/v1/auth` al final |
| `SEGURIDAD_AUDIENCIA` | Las tres | Por defecto `api-despacho`; debe coincidir en todos los procesos |
| `SEGURIDAD_ALGORITMO` | Las tres | Algoritmo asimétrico permitido. Inicialmente `RS256`, pendiente de comprobar con el emisor real |
| `PORT` | Las tres | Opcional. Por defecto 8080, 8081 o 8082 según la aplicación |
| `GESTION_DESPACHOS_URL` | Gateway | Por defecto `http://localhost:8081`; dirección interna en despliegue |
| `OPERACION_REPARTO_URL` | Gateway | Por defecto `http://localhost:8082`; dirección interna en despliegue |
| `CORS_ORIGENES` | Gateway | Orígenes exactos separados por comas, sin rutas ni barra final |
| `SOLICITUD_MAXIMO_BYTES` | Gateway | Por defecto `1048576` |
| `GATEWAY_PUBLIC_URL` | Microservicios | URL que Swagger utiliza al invocar los endpoints; por defecto `http://localhost:8080` |
| `DOCUMENTACION_HABILITADA` | Microservicios | Por defecto `false`; al habilitarla la documentación puede leerse sin token |
| `SPRING_PROFILES_ACTIVE` | Microservicios | `integracion-local` para integración simulada sin PostgreSQL |
| `SPRING_DATASOURCE_URL` | Cada microservicio fuera del perfil local | Conexión PostgreSQL propia de ese servicio |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Cada microservicio fuera del perfil local | Credenciales propias del servicio, fuera de Git |

Los orígenes locales permitidos inicialmente son los frontends en 5173 y 5174 y los Swagger UI en 8081 y 8082. En despliegue se sustituyen por los orígenes autorizados. Las fotografías se cargan directamente a Storage mediante autorización del microservicio; este límite no habilita una API de carga de archivos.

## Descubrimiento de Seguridad

Las tres aplicaciones consultan `SEGURIDAD_URL + /api/v1/auth/.well-known/openid-configuration` al procesar el primer token. Obtienen de ese documento `issuer` y `jwks_uri`; el emisor no se escribe como una constante ni se deduce del contenido de un token recibido.

Nimbus obtiene y mantiene las claves públicas, con soporte de rotación de claves. El Gateway conserva la configuración descubierta durante una hora; los microservicios la conservan durante la vida de su decodificador. Un cambio de emisor o dirección de claves requiere reiniciar los microservicios.

Salud no requiere consultar Seguridad. Si no se puede descubrir la configuración o verificar un token, la solicitud protegida se rechaza; no existe un modo que omita la autenticación.

El kit de integración compartido por G7 define `RS256`, que coincide con la configuración actual y con el valor predeterminado de [Spring Security](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html). Falta comprobar un entorno real operativo. No se aceptan automáticamente algoritmos indicados por un token.

## Enrutamiento y autorización

Los prefijos públicos configurados están en `api-gateway/src/main/resources/application.yaml`. Gestión recibe cotizaciones, despachos, seguimientos y zonas. Operación recibe repartidores, furgonetas, jornadas, flota, operaciones del repartidor, evidencias y el catálogo de motivos de fallo.

No se reescriben las rutas de negocio. Los controladores deben exponer el mismo `/api/v1/...` acordado en el contrato. No se crean alias `/vehiculos` o `/rutas` del plan provisional.

Las reglas de los microservicios reservan las integraciones para tokens `tipo=servicio` con su scope: cotización, creación, cancelación y seguimiento. Las rutas administrativas requieren un token humano con `GESTOR_DESPACHO`.

Las futuras operaciones de campo requieren `tipo=acceso`. Cada implementación deberá resolver un perfil local activo a partir de `sub` y comprobar la propiedad del recurso antes de acceder o modificar datos. Esa autorización local no se resuelve en el Gateway y todavía no existe porque los controladores operativos no están implementados. No se inventa un rol global `REPARTIDOR`.

Las rutas `/internal/v1/**` permanecen denegadas hasta implementar su autenticación y autorización específicas. No deben abrirse como rutas públicas para resolver una llamada entre microservicios.

## Perfil de integración local

`integracion-local` desactiva la creación de la conexión PostgreSQL y Flyway en ambos microservicios. Mantiene la seguridad JWT. No debe utilizarse como perfil de producción.

En Gestión habilita este recorrido:

```text
POST al Gateway → ControladorCotizacion → CalcularCotizacion → PuertoCotizacion
                                                               ↓
                                               AdaptadorCotizacionSimulada
```

El controlador no contiene valores fijos de negocio. El adaptador simulado tiene estas condiciones explícitas:

- Solo San Borja tiene cobertura.
- Solo reconoce `POL-NEG-M` y `ZAP-RUN-42`.
- Sin líneas devuelve cobertura sin costo ni plazo.
- Con líneas válidas suma los datos físicos simulados y devuelve un costo fijo de S/ 10 y dos días hábiles, sin considerar feriados.
- Sin cobertura responde antes de validar las líneas, como indica el contrato.
- Un SKU fuera del catálogo simulado devuelve 422.
- No consulta Productos ni Supabase, no crea despachos y no emite eventos.

El dato `tipoCotizacion=EXACTA` conserva el formato contractual para ejercitar a los consumidores; los valores de este adaptador son únicamente de prueba. El controlador y el caso de uso también están limitados al perfil local para evitar servir cotizaciones ficticias por accidente.

Al implementar la funcionalidad real habrá que incorporar los adaptadores de Productos, cobertura y tarifa, y habilitar el controlador y el caso de uso fuera del perfil local.

## Ejecución futura, todavía no realizada

En tres terminales independientes, situadas en sus respectivos directorios, configurar primero la URL real de Seguridad y el algoritmo acordado. Ejemplo para Gateway:

```powershell
$env:SEGURIDAD_URL = "https://<host-real-de-seguridad>"
$env:SEGURIDAD_ALGORITMO = "RS256"
.\mvnw.cmd spring-boot:run
```

Para cada microservicio, en su propia terminal:

```powershell
$env:SEGURIDAD_URL = "https://<host-real-de-seguridad>"
$env:SEGURIDAD_ALGORITMO = "RS256"
$env:SPRING_PROFILES_ACTIVE = "integracion-local"
.\mvnw.cmd spring-boot:run
```

Una vez autorizada la ejecución, comprobar salud en los puertos 8080, 8081 y 8082. Swagger UI estará en `http://localhost:8081/swagger-ui.html` y `http://localhost:8082/swagger-ui.html`; sus operaciones apuntan al Gateway. Operación mostrará inicialmente una API sin operaciones de negocio.

Obtener de Seguridad un token de servicio para el consumidor con `cotizaciones:calcular`, mediante el procedimiento de su contrato, e ingresarlo en `Authorize` de Swagger. Las credenciales técnicas se mantienen en el backend; no deben introducirse en el navegador.

También se podrá enviar una solicitud de prueba por el Gateway:

```powershell
$solicitud = @{ destino = @{ distrito = "San Borja" } } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/cotizaciones" `
  -Headers @{ Authorization = "Bearer $env:TOKEN_DESPACHO" } `
  -ContentType "application/json" -Body $solicitud
```

## Pruebas escritas y comprobaciones pendientes

Se han escrito pruebas para:

- Enrutamiento real del Gateway hacia servidores HTTP de prueba, conservando ruta, query, cuerpo, token y correlación.
- Token ausente o inválido, preflight CORS, rechazo de origen ajeno y bloqueo de rutas internas.
- Límite de cuerpo y respuesta controlada ante un destino inaccesible.
- Audiencia de servicio correcta, ajena o ausente; acceso humano sin audiencia; rechazo de refresco y ausencia de vencimiento.
- Validación compuesta de emisor y expiración.
- Descubrimiento y JWKS en un servidor de prueba, firma RSA válida y rechazo de una firma ajena.
- Cotización con y sin productos, falta de cobertura, datos inválidos y scope o tipo de token insuficientes.

Estas pruebas están pendientes de ejecución. Las pruebas de transporte y controlador sustituyen el decodificador JWT; la prueba criptográfica utiliza claves y un servidor de Seguridad de prueba. Todavía falta verificar descubrimiento, JWKS y firma con Seguridad real, Swagger UI y el recorrido entre las tres aplicaciones.

Cuando se autorice, ejecutar `.\mvnw.cmd test` en cada aplicación. Las pruebas de contexto existentes en los microservicios requieren Docker por Testcontainers. La prueba del controlador utiliza un contexto MVC sin PostgreSQL.

## Incorporación de próximos endpoints

1. Consultar el contrato oficial y confirmar el microservicio propietario.
2. Implementar el controlador y la lógica en ese microservicio.
3. Revisar su regla de autorización y agregar la comprobación local de propiedad cuando corresponda.
4. Si utiliza un prefijo público ya configurado, no necesita una ruta nueva en el Gateway.
5. Si requiere otro prefijo, incorporarlo explícitamente al Gateway y autorizarlo en el servicio.
6. Verificar errores, token, scope o rol y probar la llamada utilizando la URL del Gateway.

Cada microservicio y el Gateway se despliegan por separado. La configuración Docker y Compose se incorpora en la [guía de ejecución con Docker](ejecucion-con-docker.md). El despliegue en Render, CI/CD, rate limiting de cotización y circuit breakers siguen pendientes. El límite configurable por consumidor e IP debe resolverse antes de cerrar la cotización real. Los servicios de negocio deben usar sus conexiones de base de datos independientes y permanecer accesibles por la red interna.
