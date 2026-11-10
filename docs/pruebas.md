# Pruebas del backend de Despacho

## Recorrido local desde las apps

Iniciar el [Compose](../README.md#arranque-local) y los [frontends](https://github.com/Taller-SW-Web/G2-Despacho-Frontend#arranque-local). Desde Administración, ingresar con el gestor de prueba y su código MFA. Para probar el recorrido sin módulos externos:

1. Crear una zona activa. Agregar tarifa si también se probará la cotización.
2. Registrar un repartidor activo y vincular su cuenta desde **Más acciones > Vincular cuenta**. El UUID de Gerardo es `22222222-2222-4222-8222-222222222222` y el de Kevin, `44444444-4444-4444-8444-444444444444`. Cada identidad se vincula a un perfil.
3. Registrar una furgoneta disponible y abrir una jornada con la zona, el repartidor, el vehículo y la fecha local de Lima.
4. En **Programación**, generar un pedido de prueba y asignarlo a esa jornada. La PWA muestra el despacho al ingresar con el repartidor vinculado.
5. Cerrar la jornada con un pendiente para obtener una incidencia `FALLIDO` con motivo `NO_INTENTADO`. En Administración, confirmar la recepción en centro y cerrar el despacho como devuelto a origen.

El inicio de traslado y el cierre de jornada se pueden probar sin Storage. La entrega o el fallo con foto requieren el bucket de Operación. El flujo con un despacho simulado permite comprobar recepción, liberación y cierre sin entregar un evento a Ventas.

Para probar una API protegida sin las interfaces, solicitar el token de gestor al emisor temporal y llamar al Gateway:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/pruebas/token-usuario' -ContentType 'application/x-www-form-urlencoded' -Body @{ perfil = 'gestor' }).accessToken
Invoke-RestMethod http://localhost:8080/api/v1/distritos -Headers @{ Authorization = "Bearer $token" }
```

El token de gestor sirve para las APIs administrativas. Para cotizaciones se usa el [token de servicio del canal](../servicio-gestion-despachos/docs/pruebas-postman-local.md#7-obtener-el-token-de-servicio).

### Seguimiento RT-04

El [cargador local](../pruebas/cargar_seguimientos_local.sql) agrega ocho pedidos ficticios con prefijo `PED-SEG-` para consultar los estados de seguimiento. Cargarlo únicamente en el contenedor PostgreSQL local:

```powershell
Get-Content -Raw pruebas/cargar_seguimientos_local.sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U despacho_local -d despacho_gestion
```

Los ejemplos no crean eventos ni acreditan el ciclo operativo de Ventas o de reparto.


## Formato del código

Java usa google-java-format en estilo AOSP (4 espacios por nivel, 100 columnas) mediante Spotless; la configuración está en el `pom.xml` de cada servicio y en `.editorconfig`. Dentro de cada servicio:

```powershell
.\mvnw.cmd spotless:apply  # Aplica el formato
.\mvnw.cmd spotless:check  # Comprueba el formato sin modificar archivos
```

`seguridad-local` no es un módulo Maven y queda fuera de estos comandos.

## Pruebas y contratos

La implementación backend de ES-F04-03 y sus límites se describen en [verificación de recepción en centro](../servicio-gestion-despachos/docs/ES-F04-03-verificacion.md). Esa especificación reutiliza V1/V2. ES-F04-04 añade V3 para conservar la respuesta de reprogramación junto con su historial y evento; no modifica Operación ni su base. Las verificaciones usan bases desechables. Los datos históricos sin referencias completas requieren reconciliación verificable; los comandos los rechazan sin adivinar reservas. La trazabilidad vigente de reprogramación se mantiene en [su evidencia SDD](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F04-04-ReprogramarDespachoFallido/evidence.md).

ES-F04-05 añade V4 y `POST /api/v1/despachos/{id}/devolucion-origen`, con resultado original en el historial y evento para despachos reales. Conserva la recepción y su liberación; no llama a Operación para aceptar el cierre. Su seguimiento está en [la evidencia SDD de cierre](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F04-05-CerrarDespachoComoDevueltoAOrigen/evidence.md).

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

Para las suites completas, ejecutar `.\mvnw.cmd clean verify` en Windows o `./mvnw clean verify` en Linux con Java 21. Las integraciones requieren Docker y usan PostgreSQL desechable con Testcontainers. `clean` evita conservar migraciones antiguas en `target/classes`.

Los Dockerfiles omiten las pruebas durante la construcción: `docker compose up --build` no ejecuta estas suites. El recorrido manual registrado arriba tampoco sustituye sus resultados.

Las pruebas de Seguridad temporal están en [seguridad-local/pruebas](../seguridad-local/pruebas).

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

Comprueba el contrato vigente de [recepción en Gestión](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md#521-confirmar-recepción-en-centro--es-f04-03) pasando por Gateway: `401/403`, seis variantes inválidas con `400`, incidencia o versión obsoletas con `409`, recepción `202` con sello `false` sin observación y repetición exacta. Verifica el código de error, Problem Details, `Cache-Control: no-store` en el `202` y ausencia de escrituras ante rechazos. Tras confirmar la liberación real en Operación, exige una recepción, un historial, una solicitud y una reserva original liberada, sin incrementar intentos ni generar eventos a Ventas. La ocupación de la jornada sintética pasa de 2 kg, 0,01 m³ y un paquete a cero; repetir conserva respuesta, fecha de liberación y conteos.

Solo admite su entorno desechable; utiliza `18080/18090` y lo retira al finalizar. No apuntarlo a bases compartidas ni ejecutarlo a la vez que la prueba de humo u otro runner que use esos puertos. El informe `pruebas/resultados/contrato-recepcion-f04.json` queda fuera de Git, con resultados y datos sintéticos antes/después, sin tokens. Termina con código `1` ante un incumplimiento o error de preparación. G7 real, frontend, reinicios y aceptación en nube siguen pendientes.

Las comprobaciones propias de ambos verificadores se ejecutan con:

```powershell
python -m unittest discover -s pruebas -p 'test_verificar_*.py' -v
```

## Verificación integrada de rutas F-02

Construir los tres servicios con `./mvnw clean verify` y, desde la raíz del backend, ejecutar:

```bash
python3 pruebas/verificar_f02_rutas.py
```

Ejecutar este runner en Linux o WSL con Python 3.9+, datos de zona horaria de America/Lima, Docker Compose y los JAR verificados. Usa `/dev/null` para evitar el `.env` del proyecto. Reserva los puertos 18080 y 18090. Prepara un proyecto temporal con PostgreSQL, Seguridad local y datos sintéticos. Comprueba Gateway, Gestión, Operación y las consultas de ruta que consume F-03. Al terminar retira los contenedores de la prueba.

El runner verifica las APIs y la convergencia de la proyección. Para probar interacción en navegador con APIs simuladas, usar las suites F-02 del [frontend](https://github.com/Taller-SW-Web/G2-Despacho-Frontend#pruebas-y-construcción). Trazabilidad: [ES-F02-05](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F02-05-ConsultarReordenarSecuenciaRuta/evidence.md) y [ES-F02-06](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F02-06-ReasignarDespachoRepartidor/evidence.md).

**Cancelación ES-F02-07:** el runner comprueba anulación de un pedido con despacho fallido, rechazo de reprogramación y posterior recepción y cierre. Usa un token técnico de Ventas del emisor temporal.

`CancelarDespacho` persiste intención/cerco antes de liberar una reserva de `ASIGNADO`; confirma `200` solo tras liberación y guardado local. Los fallos/timeout conservan el cerco y se recuperan con la misma clave/cuerpo y reserva. No se debe borrar la intención ni recrear una reserva para compensar. El procesador de programación retoma pendientes con lease vencido; una referencia o motivo remoto inconsistente deja bloqueo para revisión operativa. En `FALLIDO`, solo registra la marca temporal y responde `202`; la liberación corresponde a recepción física. No se añade pantalla de cancelación administrativa. Contrato y evidencia: [Común §7.2](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md#72-cancelar-por-anulación-del-pedido), [ES-F02-07](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F02-07-CancelarDespachoPorAnulacion/evidence.md).

## Verificación F-02 publicada, sin escrituras

Con un despacho ya asignado en un entorno autorizado, `pruebas/verificar_f02_publicado.py` comprueba salud, rechazo anónimo, cola autorizada, ruta de Gestión, ruta propia de F-03, coincidencia de jornada/secuencia/ocupación y rechazo de consulta desde un segundo repartidor (`404` conforme al contrato). Solo ejecuta GET por el Gateway; no carga `.env`, no crea recursos, no sigue redirecciones ni imprime JWT o cuerpos de respuesta.

Proporcionar por un canal seguro las variables `F02_GATEWAY` (origen HTTPS; HTTP solo en loopback), `F02_GESTOR_TOKEN`, `F02_REPARTIDOR_TOKEN`, `F02_OTRO_REPARTIDOR_TOKEN`, `F02_DESPACHO` y `F02_JORNADA`. Las tres sesiones deben ser distintas y los dos repartidores deben estar activos/vinculados. Declarar también `F02_SHA_BACKEND`, `F02_SHA_FRONTEND` y `F02_SHA_DOCS` de la revisión publicada para trazabilidad; el verificador no comprueba por sí mismo esos SHA.

Desde la raíz, con Python 3:

```bash
python3 -B pruebas/verificar_f02_publicado.py
python3 -B -m unittest discover -s pruebas -p 'test_verificar_f02_publicado.py' -v
```

El primer comando escribe el informe JSON en la salida estándar. Devuelve `0` si todas las comprobaciones pasan, `1` si falla alguna y `2` si falta configuración. Ejecutarlo antes de iniciar traslado y después de converger la proyección de F-03. El reporte no acredita login/MFA, variación de capacidad antes/después, captura de ambas apps ni F-04; conservar esa evidencia aparte. Las pruebas unitarias usan dobles explícitos, no el despliegue.
