# Despliegue del backend de Despacho

## Configuración de los servicios

Los servicios usan sus Dockerfiles y perfiles `render`. Configurar las variables en Render según las plantillas de [Gateway](../api-gateway/.env.example), [Gestión](../servicio-gestion-despachos/.env.example) y [Operación](../servicio-operacion-reparto/.env.example). El Gateway recibe las URLs de los servicios; Gestión y Operación reciben sus conexiones PostgreSQL separadas.

El perfil `render` deja Flyway desactivado por defecto y usa `ddl-auto=none`. Antes de activar migraciones en una base persistente, contrastar su esquema e historial con los archivos versionados. Los datos de ejemplo y scripts bajo `src/test/resources` pertenecen a pruebas.

Las rutas internas usan el token de servicio de `modulo-despacho` sin scopes internos. Gestión y Operación contemplan una excepción de audiencia para ese cliente propio mientras G7 no emita `api-despacho` para el intercambio interno. Los clientes técnicos externos conservan los requisitos de su contrato. Configurar las credenciales de Gestión y Operación, en particular `OPERACION_CLIENT_SECRET` en Gestión para reservar o liberar capacidad.


## Pipeline CI/CD

El [workflow](../.github/workflows/ci.yaml) valida PR y cambios de las ramas de integración y entrega.

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

### Despliegue continuo

El despliegue no funciona hasta configurar esto en GitHub y Render (Settings > Secrets and variables > Actions). Mientras falten los secretos, el job `Desplegar en Render y verificar` falla en `main` con un mensaje que indica cuál falta.

| Secreto | Valor |
|---|---|
| `RENDER_DEPLOY_HOOK_OPERACION` | Deploy hook de `taller-servicio-operacion-reparto` (Render > servicio > Settings > Deploy Hook) |
| `RENDER_DEPLOY_HOOK_GESTION` | Deploy hook de `taller-servicio-gestion-despachos` |
| `RENDER_DEPLOY_HOOK_GATEWAY` | Deploy hook de `taller-api-gateway` |

1. En Render, en cada servicio, poner **Auto-Deploy en `Off`**. Si queda en `On`, cada push despliega sin esperar a las pruebas y el job duplica el despliegue.
2. Crear en GitHub el entorno `produccion` (Settings > Environments). Opcional: añadir *Required reviewers* para exigir aprobación manual antes de desplegar.
3. Las URLs verificadas son las predeterminadas de `herramientas/mantener_backends_activos.py`; si cambian en Render, actualizar esa herramienta.
4. Para redesplegar sin un commit nuevo: Actions > CI/CD backend > *Run workflow* sobre `main`. Las ejecuciones manuales sobre otras ramas solo compilan y prueban.

Para exigir el resultado antes de integrar, configurar la protección de `develop` y `main` en GitHub y seleccionar la compuerta `CI backend verde` cuando haya aparecido en una ejecución. Resume las pruebas de los tres servicios, de `seguridad-local`, del verificador F-02 y la construcción de las imágenes, y no depende de los nombres de la matriz. Si se prefiere exigirlas por separado, también existen `Pruebas (api-gateway)`, `Pruebas (servicio-gestion-despachos)`, `Pruebas (servicio-operacion-reparto)` y `Pruebas (seguridad-local)`.

La protección de ramas se configura en GitHub; añadir el workflow por sí solo no impide un merge. Se propone exigir PR, una aprobación de otro compañero, comprobaciones aprobadas y conversaciones resueltas, sin force push ni eliminación de las ramas protegidas.


## Consultas periódicas de salud desde el PC

[herramientas/mantener_backends_activos.py](../herramientas/mantener_backends_activos.py) consulta directamente `/actuator/health` del Gateway, Gestión y Operación cada cinco minutos. Requiere Python 3.9 o posterior, sin paquetes adicionales, cuentas ni `.env`. Desde la raíz del backend:

```powershell
python herramientas/mantener_backends_activos.py
```

Mantener la terminal abierta; `Ctrl+C` detiene el script. El PC debe tener conexión y permanecer encendido, sin suspensión. Las peticiones son secuenciales, con una espera máxima predeterminada de 75 segundos para permitir arranques lentos; no se superponen. Un `429` u otra respuesta con `Retry-After` retrasa la siguiente consulta de ese servicio. No se guardan tokens ni cuerpos de respuestas, y una página HTML de arranque no se informa como salud `UP`.

Para cambiar el intervalo, comprobar una sola ronda o sustituir las URLs públicas:

```powershell
python herramientas/mantener_backends_activos.py --intervalo 300 --timeout 75
python herramientas/mantener_backends_activos.py --una-vez
python herramientas/mantener_backends_activos.py --una-vez --con-dependencias
python herramientas/mantener_backends_activos.py --url https://HOST-REAL/actuator/health
```

`--con-dependencias` agrega la salud de Seguridad (G7) y del Gateway de Productos (G6). El Gateway de G6 debe responder `status=ok`; los demás servicios, `status=UP`. Para comprobar que Despacho obtiene los datos físicos de G6, ejecutar además una cotización con SKU.

`--url` se puede repetir para sustituir los tres destinos predeterminados y se usa por separado de `--con-dependencias`. `--una-vez` termina con código `0` cuando todos responden `200` y el estado esperado, o `1` si alguno falla. El intervalo mínimo es 60 segundos. Para verificar el consultor sin llamar a Render: `python -B -m unittest discover -s pruebas -p test_mantener_backends_activos.py -v`.

Las consultas reducen la inactividad y ayudan a diagnosticar disponibilidad. No corrigen errores de SQL, migraciones o permisos.
