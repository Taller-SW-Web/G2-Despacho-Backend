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


## Pipeline CI/CD

El [workflow](../.github/workflows/ci.yaml) valida PR y cambios de las ramas de integración y entrega.

```
PR / push a develop o main
  1. Compilar y probar   verificador F-02 · Pruebas (api-gateway | gestión | operación) · seguridad-local
     Seguridad           Seguridad (dependencias Maven) · Seguridad (secretos en el historial)
  2. Construir imágenes  Imagen Docker (api-gateway | gestión | operación | seguridad-local)
                         + SBOM CycloneDX y escaneo Trivy de cada imagen
  3. Compuerta           CI backend verde (incluye toda la revisión de seguridad)
  4. Desplegar           solo push o ejecución manual en main  ->  Render (Operación, Gestión, Gateway)
  5. Verificar           /actuator/health de los tres servicios (herramientas/mantener_despacho_activo.py)
```

- **Compilar y probar:** `clean verify` por servicio, con PostgreSQL/PostGIS temporal (Testcontainers).
- **Construir imágenes:** cada Dockerfile se construye con `docker build` en cada ejecución, de modo que un Dockerfile roto falla antes de integrar. En `main` las imágenes de Gateway, Gestión y Operación se publican en GHCR (`ghcr.io/<organización>/<repositorio>/<servicio>`) con las etiquetas del SHA del commit y `latest`. `seguridad-local` se construye pero nunca se publica ni se despliega: es un emisor de pruebas que no debe usarse en un despliegue público.
- **Compuerta:** el job `CI backend verde` falla si cualquier etapa anterior no terminó en `success`. El despliegue depende únicamente de él.
- **Desplegar:** en `main`, con el entorno `produccion` de GitHub, se llama a los deploy hooks de Render con el SHA del commit (`&ref=<SHA>`), primero Operación y Gestión y al final el Gateway. Solo hay un despliegue a la vez (`concurrency`) y nunca se cancela uno en curso.
- **Verificar:** tras una espera inicial, se reutiliza `herramientas/mantener_despacho_activo.py --una-vez` hasta que los tres servicios respondan `200` y `status=UP`, con reintentos de hasta unos 15 minutos. Esto acredita que los servicios están disponibles tras el despliegue; no identifica la versión (el endpoint de salud no la expone).

### Revisión de seguridad

La revisión de seguridad forma parte del pipeline y bloquea la integración y el despliegue. Cubre los riesgos A02, A03 y A08 del OWASP Top 10:2025.

| Control | Job | Herramienta | Falla cuando |
|---|---|---|---|
| Dependencias vulnerables (A03) | `Seguridad (dependencias Maven)` | OWASP Dependency-Check 13.0.0 | Una dependencia de Gateway, Gestión u Operación tiene un CVE con CVSS >= 7. |
| Secretos expuestos (A02, A08) | `Seguridad (secretos en el historial)` | gitleaks 8.30.1 | Hay una credencial en cualquier commit del historial. |
| Imagen vulnerable (A03) | `Imagen Docker (<servicio>)` | Trivy 0.76.2 | La imagen tiene vulnerabilidades CRITICAL o HIGH que ya tienen corrección. La imagen no se publica en GHCR. |
| Inventario de componentes (A03) | `Imagen Docker (<servicio>)` | Trivy (SBOM CycloneDX) | No falla; deja el SBOM como artefacto. |
| Dependencias desactualizadas (A03) | Dependabot | `.github/dependabot.yml` | No falla; abre PR semanales hacia `develop`. |

Los informes quedan 30 días como artefactos de la ejecución: `seguridad-dependencias`, `seguridad-secretos` y `seguridad-imagen-<servicio>`. El informe de gitleaks oculta los valores encontrados (`--redact`).

Integridad del propio pipeline (A08):

- Las actions se fijan por SHA de commit, no por etiqueta.
- gitleaks y Trivy se descargan por versión fija y se verifica su SHA-256 antes de ejecutarlos. No se usa `aquasecurity/trivy-action`, cuyas etiquetas fueron reescritas con malware en marzo de 2026 (CVE-2026-33634).
- El token del workflow es de solo lectura (`contents: read`). Solo el job de imágenes puede escribir paquetes.
- `persist-credentials: false` evita que el token quede guardado en el checkout.
- Los secretos se leen desde GitHub Actions y nunca se escriben en el código.

Si un control falla:

- **Dependencia o imagen vulnerable:** actualizar la dependencia o la imagen base. Si es un falso positivo, agregar una supresión en `.github/seguridad/dependency-check-supresiones.xml` con el CVE y el motivo.
- **Secreto encontrado:** primero revocar y rotar la credencial; después limpiar el historial. Borrar el archivo no basta, porque el historial conserva el valor.

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
