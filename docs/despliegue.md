# Despliegue del backend de Despacho

## Configuración de los servicios

Los servicios usan sus Dockerfiles y perfiles `render`. Configurar las variables en Render según las plantillas de [Gateway](../api-gateway/.env.example), [Gestión](../servicio-gestion-despachos/.env.example) y [Operación](../servicio-operacion-reparto/.env.example). El Gateway recibe las URLs de los servicios; Gestión y Operación reciben sus conexiones PostgreSQL separadas.

El perfil `render` deja Flyway desactivado por defecto y usa `ddl-auto=none`. Antes de activar migraciones en una base persistente, contrastar su esquema e historial con los archivos versionados. Los datos de ejemplo y scripts bajo `src/test/resources` pertenecen a pruebas.

Las rutas internas usan el token de servicio de `modulo-despacho` sin scopes internos. Gestión y Operación contemplan una excepción de audiencia para ese cliente propio mientras G7 no emita `api-despacho` para el intercambio interno. Los clientes técnicos externos conservan los requisitos de su contrato. Configurar las credenciales de Gestión y Operación, en particular `OPERACION_CLIENT_SECRET` en Gestión para reservar o liberar capacidad.

### Eventos hacia Ventas

Gestión envía los cambios de estado al webhook de Ventas desde la bandeja `eventos_salientes`. Configurar en Gestión:

| Variable | Valor |
|---|---|
| `VENTAS_URL` | Origen de Ventas, sin `/api/v1`. Gestión agrega `/api/v1/integraciones/despachos/eventos`. |
| `VENTAS_CLIENT_ID` | `modulo-despacho`. |
| `VENTAS_CLIENT_SECRET` | Secreto de `modulo-despacho` emitido por G7. Es el mismo cliente que usan Productos y Operación; el token se pide con el scope `ventas:pedidos:estado` y G7 debe concederlo con `aud=api-ventas`. |
| `VENTAS_TIMEOUT_CONEXION_MS` / `VENTAS_TIMEOUT_LECTURA_MS` | Opcionales. Por defecto, 3000 y 5000 ms. |

El ciclo de recuperación revisa la bandeja cada 30 segundos mientras Gestión está activo. Un evento hacia Ventas tiene un envío y hasta cinco reintentos con espera creciente (30 s, 1, 2, 4 y 8 minutos). Cualquier respuesta distinta de `2xx` o un tiempo agotado cuenta como fallo. Agotados los reintentos, queda en `ENVIO_FALLIDO`.

Un gestor puede devolver ese evento a la cola con `POST /api/v1/despachos/{idDespacho}/eventos-ventas/{idEvento}/reenvio`. Requiere el rol `GESTOR_DESPACHO` y responde `202` con `estadoEnvio=PENDIENTE`. El contenido y el `idEvento` no cambian, de modo que Ventas sigue deduplicando. Responde `404 DESP_ERROR_EVENTO_NO_ENCONTRADO` si el evento no pertenece a ese despacho o no va dirigido a Ventas, y `409 DESP_ERROR_EVENTO_NO_REENVIABLE` con `estadoActual` si no está en `ENVIO_FALLIDO`. Los identificadores de los eventos aparecen en las comunicaciones del detalle de la incidencia.

### RabbitMQ para eventos internos

RabbitMQ es un transporte opcional y aditivo entre Gestión y Operación. No sustituye las API REST internas ni el webhook a Ventas. Se activa con `RABBITMQ_HABILITADO=true` en ambos servicios; con el valor por defecto `false` el comportamiento es el anterior. En Render no hay broker desplegado: para activarlo hay que contratar uno externo y configurar sus credenciales.

| Variable | Servicios | Valor |
|---|---|---|
| `RABBITMQ_HABILITADO` | Gestión y Operación | `false` por defecto; `true` publica y consume por RabbitMQ. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | Gestión y Operación | Origen AMQP, por defecto `localhost:5672`; en Compose, `rabbitmq:5672`. |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | Gestión y Operación | Credenciales del broker, distintas de los tokens `client_credentials`. |
| `RABBITMQ_VIRTUAL_HOST` | Gestión y Operación | Host virtual, `/` por defecto. |
| `RABBITMQ_SALUD_HABILITADA` | Gestión y Operación | `false` por defecto para no marcar `DOWN` el health check cuando no hay broker. |

Los exchanges son `despacho.capacidad` (`operacion.liberacion.{idReserva}`) y `despacho.estado` (`despacho.proyeccion`); las colas `operacion.liberaciones` y `despacho.eventos.estado` pertenecen a Operación y tienen su DLQ. El productor espera confirmación del broker antes de marcar el evento como enviado en `eventos_salientes`. El consumidor registra `eventId` en `eventos_procesados` (V8 de Operación) antes de ejecutar la liberación o la proyección; una reentrega se descarta. Los mensajes no procesables van a la DLQ.

Para verificar localmente, levantar Compose con `RABBITMQ_HABILITADO=true` y comprobar las colas en `http://localhost:15672` con las credenciales del `.env.example` raíz. Para volver al flujo anterior, poner el flag en `false` y recrear los servicios: los endpoints internos HTTP siguen operativos. El contrato del sobre está en el [contrato externo](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md#36-mensajería-interna-con-rabbitmq) §3.6.

### Acceso solo por el Gateway en Render Free

Render Free no ofrece Private Services, así que Gestión y Operación conservan una URL pública. Para que el Gateway sea la única entrada utilizable, el Gateway añade la cabecera `X-Despacho-Interno` con un secreto compartido en las rutas `/api/v1/**`. Gestión y Operación responden `404 DESP_ERROR_RUTA_NO_ENCONTRADA` a cualquier llamada directa a `/api/v1/**` que no la traiga o la traiga con otro valor. El filtro se ejecuta antes que la autenticación, de modo que la respuesta no revela si la ruta existe.

| Variable | Servicio | Valor |
|---|---|---|
| `INTERNO_SECRETO` | Gateway, Gestión y Operación | El mismo valor en los tres, por ejemplo el resultado de `openssl rand -hex 32`. Vacío desactiva la simulación. |
| `INTERNO_REQUERIDO` | Gestión y Operación | `false` por defecto. Con `true` se bloquean las llamadas directas; exige `INTERNO_SECRETO` no vacío o el servicio no arranca. |

No se bloquean `/actuator/**` (health check de Render y `herramientas/mantener_despacho_activo.py`), `/internal/v1/**` (protegido por el token de servicio), `/api/v1/auth/**` ni las solicitudes `OPTIONS` de CORS. El Gateway descarta la cabecera que envíe el cliente y nunca la reenvía a Seguridad. La única llamada entre servicios a una ruta `/api/v1/**` es la consulta de zona de Operación a Gestión (`GET /api/v1/zonas/{id}`), que también envía el secreto.

Activar en dos fases, sin mezclar este cambio con una rotación del secreto:

1. **Preparar sin bloquear.** Configurar `INTERNO_SECRETO` en los tres servicios e `INTERNO_REQUERIDO=false` en Gestión y Operación. Desplegar y comprobar que los frontends y los módulos externos siguen funcionando a través del Gateway.
2. **Bloquear.** Antes, confirmar con los equipos externos que ninguno llama directamente a las URLs `onrender.com` de Gestión u Operación. Poner `INTERNO_REQUERIDO=true` en Gestión y Operación y redesplegar ambos.

Para verificar, sustituyendo `<secreto>` por el valor configurado:

```bash
curl -i https://taller-api-gateway.onrender.com/api/v1/zonas                  # 401: el Gateway exige JWT
curl -i https://taller-servicio-gestion-despachos.onrender.com/api/v1/zonas   # 404: acceso directo bloqueado
curl -i -H "X-Despacho-Interno: <secreto>" \
  https://taller-servicio-gestion-despachos.onrender.com/api/v1/zonas         # 401: pasa el filtro, falta el JWT
curl -i https://taller-servicio-gestion-despachos.onrender.com/actuator/health  # 200
```

Comprobar además el login por el Gateway, la apertura de una jornada (Operación consulta la zona en Gestión) y la asignación o liberación de capacidad. Si al abrir una jornada aparece «La zona no existe.» con una zona válida, Operación no tiene el mismo `INTERNO_SECRETO` que Gestión.

**Rollback:** poner `INTERNO_REQUERIDO=false` en Gestión y Operación y redesplegarlos. El Gateway puede seguir enviando la cabecera sin efecto. Si el problema era un consumidor externo que llamaba directo, migrarlo al Gateway antes de volver a activar el bloqueo.

**Límites:** no es un enmascaramiento real. Las URLs públicas siguen existiendo, `/actuator/health` sigue visible y `/internal/v1/**` depende solo del token de servicio. Si el secreto se filtra, se puede saltar el Gateway, por lo que debe rotarse en los tres servicios a la vez. El aislamiento real requiere Private Services (plan de pago) o un túnel o WAF delante de los servicios.


## Pipeline CI/CD

El [workflow](../.github/workflows/ci.yaml) valida PR y cambios de las ramas de integración y entrega.

```
PR / push a develop o main
  1. Compilar y probar   verificador F-02 · Pruebas (api-gateway | gestión | operación) · seguridad-local
     Seguridad           Seguridad (dependencias Maven) · Seguridad (secretos en el historial)
                         · Seguridad (código java-kotlin | actions) con CodeQL
  2. Construir imágenes  Imagen Docker (api-gateway | gestión | operación | seguridad-local)
                         + SBOM CycloneDX y escaneo Trivy de cada imagen
  3. Compuerta           CI backend verde (incluye toda la revisión de seguridad)
  4. Desplegar           solo push o ejecución manual en main  ->  Render (Operación, Gestión, Gateway)
  5. Verificar           /actuator/health · commit desplegado en /actuator/info · 401 y 404 de seguridad
```

- **Compilar y probar:** `clean verify` por servicio, con PostgreSQL/PostGIS temporal (Testcontainers).
- **Construir imágenes:** cada Dockerfile se construye con `docker build` en cada ejecución, de modo que un Dockerfile roto falla antes de integrar. En `main` las imágenes de Gateway, Gestión y Operación se publican en GHCR (`ghcr.io/<organización>/<repositorio>/<servicio>`) con las etiquetas del SHA del commit y `latest`. `seguridad-local` se construye pero nunca se publica ni se despliega: es un emisor de pruebas que no debe usarse en un despliegue público.
- **Compuerta:** el job `CI backend verde` falla si cualquier etapa anterior no terminó en `success`. El despliegue depende únicamente de él.
- **Desplegar:** en `main`, con el entorno `produccion` de GitHub, se llama a los deploy hooks de Render con el SHA del commit (`&ref=<SHA>`), primero Operación y Gestión y al final el Gateway. Solo hay un despliegue a la vez (`concurrency`) y nunca se cancela uno en curso.
- **Verificar:** tras una espera inicial, se reutiliza `herramientas/mantener_despacho_activo.py --una-vez` hasta que los tres servicios respondan `200` y `status=UP`, con reintentos de hasta unos 15 minutos. Luego se espera, hasta unos 10 minutos más, a que cada servicio informe en `/actuator/info` (`despliegue.commit`, tomado de `RENDER_GIT_COMMIT`) el mismo SHA que se desplegó: así un `UP` de la versión anterior no da el despliegue por bueno. Si Render no informa la variable, se deja una advertencia. Por último, un smoke test de seguridad comprueba que el Gateway responde `401` a `/api/v1/zonas` sin token y que Gestión (`/api/v1/zonas`) y Operación (`/api/v1/jornadas`) responden `404` si se les llama directo, sin `X-Despacho-Interno`.

### Revisión de seguridad

La revisión de seguridad forma parte del pipeline y bloquea la integración y el despliegue. Cubre los riesgos A02, A03, A04, A05, A07 y A08 del OWASP Top 10:2025.

| Control | Job | Herramienta | Falla cuando |
|---|---|---|---|
| Dependencias vulnerables (A03) | `Seguridad (dependencias Maven)` | OWASP Dependency-Check 13.0.0 | Una dependencia de Gateway, Gestión u Operación tiene un CVE con CVSS >= 7. |
| Secretos expuestos (A02, A08) | `Seguridad (secretos en el historial)` | gitleaks 8.30.1 | Hay una credencial en cualquier commit del historial. |
| Imagen vulnerable (A03) | `Imagen Docker (<servicio>)` | Trivy 0.76.2 | La imagen tiene vulnerabilidades CRITICAL o HIGH que ya tienen corrección. La imagen no se publica en GHCR. |
| Inventario de componentes (A03) | `Imagen Docker (<servicio>)` | Trivy (SBOM CycloneDX) | No falla; deja el SBOM como artefacto. |
| Código propio vulnerable, SAST (A02, A04, A05, A07) | `Seguridad (código java-kotlin)` | CodeQL 4.39.5, suite `security-extended`, sin compilar | Un hallazgo del código de los servicios tiene `security-severity` >= 7 (por ejemplo, inyección SQL, JWT sin verificar, traza de error expuesta o credencial en el código). |
| Pipeline inseguro (A08) | `Seguridad (código actions)` | CodeQL sobre `.github/workflows` | Un workflow tiene un hallazgo con `security-severity` >= 7 (por ejemplo, inyección de datos del evento en un `run:`). |

Los informes quedan 30 días como artefactos de la ejecución: `seguridad-dependencias`, `seguridad-secretos`, `seguridad-codigo-<lenguaje>` (SARIF) y `seguridad-imagen-<servicio>`. El informe de gitleaks oculta los valores encontrados (`--redact`). Los hallazgos de CodeQL, también los de severidad menor que 7 (que no bloquean), se publican en **Security > Code scanning**. CodeQL se configura en `.github/codeql/codeql-config.yml`, que excluye las pruebas y los dobles `seguridad-local` y `ventas-local` (no se despliegan).

Requisito: en **Settings > Code security**, el *Default setup* de CodeQL debe estar desactivado. Este workflow usa la configuración avanzada y ambas no pueden convivir.

Integridad del propio pipeline (A08):

- Las actions se fijan por SHA de commit, no por etiqueta.
- gitleaks y Trivy se descargan por versión fija y se verifica su SHA-256 antes de ejecutarlos. No se usa `aquasecurity/trivy-action`, cuyas etiquetas fueron reescritas con malware en marzo de 2026 (CVE-2026-33634).
- El token del workflow es de solo lectura (`contents: read`). Solo el job de imágenes puede escribir paquetes.
- `persist-credentials: false` evita que el token quede guardado en el checkout.
- Los secretos se leen desde GitHub Actions y nunca se escriben en el código.

Si un control falla:

- **Dependencia o imagen vulnerable:** actualizar la dependencia o la imagen base. Si es un falso positivo, agregar una supresión en `.github/seguridad/dependency-check-supresiones.xml` con el CVE y el motivo.
- **Secreto encontrado:** primero revocar y rotar la credencial; después limpiar el historial. Borrar el archivo no basta, porque el historial conserva el valor.
- **Hallazgo de CodeQL:** abrir la alerta en Security > Code scanning, que muestra el recorrido del dato, y corregir el código (consultas parametrizadas, validación con allowlist, errores genéricos). Si es un falso positivo, descartarlo en la alerta con el motivo o excluir la regla en `query-filters` de `.github/codeql/codeql-config.yml` con un comentario que lo justifique.

### Despliegue continuo

El despliegue no funciona hasta configurar esto en GitHub y Render (Settings > Secrets and variables > Actions). Mientras falten los secretos, el job `Desplegar en Render y verificar` falla en `main` con un mensaje que indica cuál falta.

| Secreto | Valor |
|---|---|
| `RENDER_DEPLOY_HOOK_OPERACION` | Deploy hook de `taller-servicio-operacion-reparto` (Render > servicio > Settings > Deploy Hook) |
| `RENDER_DEPLOY_HOOK_GESTION` | Deploy hook de `taller-servicio-gestion-despachos` |
| `RENDER_DEPLOY_HOOK_GATEWAY` | Deploy hook de `taller-api-gateway` |
| `NVD_API_KEY` | Clave gratuita de la API de NVD (https://nvd.nist.gov/developers/request-an-api-key). Sin ella, Dependency-Check descarga la base de vulnerabilidades muy lentamente. |

1. En Render, en cada servicio, poner **Auto-Deploy en `Off`**. Si queda en `On`, cada push despliega sin esperar a las pruebas y el job duplica el despliegue.
2. Crear en GitHub el entorno `produccion` (Settings > Environments). Opcional: añadir *Required reviewers* para exigir aprobación manual antes de desplegar.
3. Las URLs verificadas son las predeterminadas de `herramientas/mantener_despacho_activo.py`; si cambian en Render, actualizar esa herramienta.
4. Para redesplegar sin un commit nuevo: Actions > CI/CD backend > *Run workflow* sobre `main`. Las ejecuciones manuales sobre otras ramas solo compilan y prueban.

Para exigir el resultado antes de integrar, configurar la protección de `develop` y `main` en GitHub y seleccionar la compuerta `CI backend verde` cuando haya aparecido en una ejecución. Resume las pruebas de los tres servicios, de `seguridad-local`, del verificador F-02 y la construcción de las imágenes, y no depende de los nombres de la matriz. Si se prefiere exigirlas por separado, también existen `Pruebas (api-gateway)`, `Pruebas (servicio-gestion-despachos)`, `Pruebas (servicio-operacion-reparto)` y `Pruebas (seguridad-local)`.

La protección de ramas se configura en GitHub; añadir el workflow por sí solo no impide un merge. Se propone exigir PR, una aprobación de otro compañero, comprobaciones aprobadas y conversaciones resueltas, sin force push ni eliminación de las ramas protegidas.


## Consultas periódicas de salud desde el PC

[herramientas/mantener_despacho_activo.py](../herramientas/mantener_despacho_activo.py) consulta directamente `/actuator/health` del Gateway, Gestión y Operación cada cinco minutos. Requiere Python 3.9 o posterior, sin paquetes adicionales, cuentas ni `.env`. Desde la raíz del backend:

```powershell
python herramientas/mantener_despacho_activo.py
```

Mantener la terminal abierta; `Ctrl+C` detiene el script. El PC debe tener conexión y permanecer encendido, sin suspensión. Las peticiones son secuenciales, con una espera máxima predeterminada de 75 segundos para permitir arranques lentos; no se superponen. Un `429` u otra respuesta con `Retry-After` retrasa la siguiente consulta de ese servicio. No se guardan tokens ni cuerpos de respuestas, y una página HTML de arranque no se informa como salud `UP`.

Para cambiar el intervalo, comprobar una sola ronda o sustituir las URLs públicas:

```powershell
python herramientas/mantener_despacho_activo.py --intervalo 300 --timeout 75
python herramientas/mantener_despacho_activo.py --una-vez
python herramientas/mantener_despacho_activo.py --una-vez --con-dependencias
python herramientas/mantener_despacho_activo.py --url https://HOST-REAL/actuator/health
```

`--con-dependencias` agrega la salud de Seguridad (G7) y del Gateway de Productos (G6). El Gateway de G6 debe responder `status=ok`; los demás servicios, `status=UP`. Para comprobar que Despacho obtiene los datos físicos de G6, ejecutar además una cotización con SKU.

`--url` se puede repetir para sustituir los tres destinos predeterminados y se usa por separado de `--con-dependencias`. `--una-vez` termina con código `0` cuando todos responden `200` y el estado esperado, o `1` si alguno falla. El intervalo mínimo es 60 segundos. Para verificar el consultor sin llamar a Render: `python -B -m unittest discover -s pruebas -p test_mantener_despacho_activo.py -v`.

Las consultas reducen la inactividad y ayudan a diagnosticar disponibilidad. No corrigen errores de SQL, migraciones o permisos.

Para mantener activas también las dependencias de la cotización (Seguridad G7 y Productos G6) o levantar todo a la vez, ver [herramientas/README.md](../herramientas/README.md).
