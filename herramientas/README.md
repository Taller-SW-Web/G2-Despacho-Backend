# Herramientas: mantener activos los servicios

Los servicios del plan free de Render se apagan tras **15 minutos sin tráfico**, y Productos (G6),
en Azure Container Apps, también se apaga cuando no recibe peticiones. Si un servicio está apagado,
la primera petición tarda en responder mientras arranca, y las llamadas entre servicios pueden fallar
por timeout (por ejemplo, `503 DESP_ERROR_PRODUCTOS_NO_DISPONIBLE` al cotizar).

Estos scripts hacen consultas periódicas **de solo lectura** para que los servicios no se duerman,
por ejemplo mientras otro grupo hace pruebas de integración. No envían tokens, no siguen
redirecciones y no modifican nada.

Requisitos: Python 3.9 o posterior. No hay que instalar paquetes, ni hace falta `.env` o credenciales.

## Los scripts

| Script | Qué mantiene activo | Qué consulta | Intervalo por defecto |
|---|---|---|---|
| `mantener_despacho_activo.py` | Nuestros backends: Gateway, Gestión y Operación (Render) | `GET /actuator/health` de cada uno; espera `status=UP` | 300 s (5 min) |
| `mantener_seguridad_activo.py` | Seguridad de G7 (`g7-auth-service`, Render) | `GET /actuator/health`; espera `status=UP` | 240 s (4 min) |
| `mantener_productos_activo.py` | Productos de G6 (Azure Container Apps) | `GET /health`; espera `status=ok` | 120 s (2 min) |
| `mantener_todo_activo.py` | Los tres anteriores a la vez | Ejecuta los tres scripts en paralelo | El de cada script |

Cada script es **independiente**: se puede copiar y ejecutar solo, sin los demás. Los tres comparten
el mismo código de consulta (incluido dentro de cada uno), así que se comportan y muestran la salida
igual. Si se cambia ese código en uno, conviene cambiarlo también en los otros dos.
`mantener_todo_activo.py` solo lanza los otros tres, así que necesita que estén en la misma carpeta.

### `mantener_despacho_activo.py`

Mantiene despiertos nuestros tres backends. El CI también lo usa (`--una-vez`) para verificar que los
servicios quedaron `UP` después de cada despliegue, así que **si cambian las URLs en Render hay que
actualizarlas aquí**. Además tiene dos opciones propias:

- `--con-dependencias`: agrega la salud de Seguridad (G7) y del gateway de Productos (G6).
- `--url URL`: sustituye los destinos predeterminados; se puede repetir.

### `mantener_seguridad_activo.py`

Mantiene despierto G7. Despacho depende de G7 para dos cosas: el Gateway valida con G7 los tokens que
llegan, y Gestión le pide a G7 un token de servicio antes de llamar a Productos. Si G7 está dormido, esas
llamadas se demoran y pueden fallar con `401` (timeout de OpenID) o `503`.

### `mantener_productos_activo.py`

Mantiene despierto Productos consultando su `/health`, que responde `status=ok` (no `UP` como los
demás). El intervalo es más corto (2 min) porque Azure apaga los contenedores antes que Render. Productos no lo mantiene siempre encendido por costos: **detén este script cuando terminen las
pruebas**.

### `mantener_todo_activo.py`

Levanta los tres scripts a la vez en una sola ventana. Cada línea lleva un prefijo (`[Despacho ]`,
`[Seguridad]`, `[Productos]`) para saber de qué script viene. `Ctrl+C` detiene los tres.

## Cómo usarlos

Desde la raíz del backend (`G2-Despacho-Backend`), en PowerShell:

```powershell
# Todo a la vez (lo más simple)
python herramientas/mantener_todo_activo.py

# O cada uno por separado, en su propia ventana
python herramientas/mantener_despacho_activo.py
python herramientas/mantener_seguridad_activo.py
python herramientas/mantener_productos_activo.py
```

Mantén la ventana abierta y el PC encendido, sin suspensión. Para detener, pulsa `Ctrl+C`.

### Opciones comunes

Los tres scripts aceptan las mismas opciones:

| Opción | Qué hace |
|---|---|
| `--intervalo N` | Segundos entre consultas (mínimo 60). |
| `--timeout N` | Espera máxima por petición, en segundos (por defecto 75, para dar tiempo a un arranque lento). |
| `--una-vez` | Consulta una sola vez y termina: código `0` si todo respondió bien, `1` si algo falló. |

`mantener_todo_activo.py` acepta `--una-vez`, que se pasa a los tres scripts.

```powershell
# Comprobar rápidamente si todo está arriba
python herramientas/mantener_todo_activo.py --una-vez

# Consultar Productos cada 90 segundos
python herramientas/mantener_productos_activo.py --intervalo 90
```

## Cómo leer la salida

```
2026-11-14T00:30:00-05:00 [OK] Gateway: HTTP 200; salud UP (0.3 s). Próxima consulta en 300 s.
2026-11-14T00:30:02-05:00 [AVISO] Productos (G6): sin respuesta (TimeoutError) (75.0 s). Próxima consulta en 120 s.
```

- `[OK]`: el servicio respondió `200` con la salud esperada.
- `[AVISO]`: no respondió, respondió con error o devolvió algo inesperado, como la página HTML que
  muestra Render mientras arranca. Si un `[AVISO]` tarda muchos segundos, probablemente el servicio
  estaba arrancando; la siguiente consulta debería salir `[OK]`.
- Si un servicio responde `429` con `Retry-After`, el script espera ese tiempo antes de volver a
  consultarlo.

## Pruebas

```powershell
python -B -m unittest discover -s pruebas -p test_mantener_despacho_activo.py -v
```

Levantan un servidor HTTP local, así que no llaman a Render.
