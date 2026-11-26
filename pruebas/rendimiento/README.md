# Pruebas de rendimiento del hito 5 (JMeter)

Planes de **PERF-02** (cola de pendientes, carga sostenida), **PERF-05** (Mi Ruta, carga sostenida) y **PERF-06** (Mi Ruta, pico y recuperación). Se mide el tiempo total de respuesta HTTP desde el cliente, columna `elapsed` del JTL.

## Requisitos

- Apache JMeter **5.6.3** ejecutado con **Java 21**. El Groovy 3.0.20 que trae JMeter 5.6.3 no lee clases de Java 23 ni de Java 25, y con esas versiones fallan los elementos JSR223. Esto solo afecta al generador de carga. Los servicios siguen en Java 25.
- Docker en ejecución y los JAR de los servicios construidos con `clean verify`.
- Python 3 para el análisis.

## 1. Preparar los datos

Desde `pruebas/integracion-java`:

```powershell
.\mvnw.cmd test -Dtest=PreparacionRutasCarga
```

Este comando:

- Levanta el entorno Compose `hito5-perf` y **lo deja activo**.
- Siembra 30 repartidores vinculados, cada uno con su jornada activa y 10 despachos. Sus identidades son los perfiles `repartidor-carga-01` a `repartidor-carga-30` de `seguridad-local`.
- Espera a que Mi Ruta coincida con la ruta de Gestión.
- Escribe `datos/rutas-carga.csv` (`indice,idJornada,despachos`, con despachos `id:secuencia`). El archivo no lleva tokens y está fuera de Git.

PERF-05 y PERF-06 reutilizan estos datos, porque solo leen.

Para **PERF-02**, retirar primero el entorno anterior y ejecutar:

```powershell
.\mvnw.cmd test -Dtest=PreparacionColaCarga
```

Este comando:

- Levanta un entorno `hito5-perf` limpio.
- Siembra **exactamente 10.000** despachos en `PENDIENTE_ASIGNACION` por la API de simulación, uno tras otro. Tarda unos 50 s.
- Comprueba el total con SQL.
- Escribe `datos/paginas-cola.csv` (`pagina,ids`) con los IDs esperados de las páginas 1, 50 y 100.

La cola ordena por `fecha_programada`, `creado_en` e `id`. Como todos los despachos son de hoy y se crean en secuencia, el orden esperado es el de creación y no depende de la respuesta del sistema bajo prueba. La preparación lo contrasta con la API antes de terminar.

No hay que mezclar las dos preparaciones en el mismo entorno: la cola tendría más de 10.000 pendientes, o las rutas compartirían la base. Para retirar el entorno: `docker compose -p hito5-perf down --volumes`.

## 2. Ejecutar

```bash
export JAVA_HOME=/ruta/a/jdk-21
JMETER=/ruta/a/apache-jmeter-5.6.3/bin/jmeter ./ejecutar.sh PERF-02
JMETER=/ruta/a/apache-jmeter-5.6.3/bin/jmeter ./ejecutar.sh PERF-05
JMETER=/ruta/a/apache-jmeter-5.6.3/bin/jmeter ./ejecutar.sh PERF-06
```

Cada corrida crea `resultados/<caso>-<fecha>/` con:

- `<caso>.jtl`: todas las muestras.
- `html/`: el informe de JMeter, solo con las fases medidas.
- `recursos.csv`: CPU y RAM de cada contenedor, cada 5 s.
- `resumen.txt` y `resumen.json`: resultado por fase.
- `commit.txt` y `version-jmeter.txt`.

No hay que superponer corridas en la misma instancia.

## Diseño de los planes

- **Sesiones fuera de la medición.** El grupo `SETUP-sesiones` obtiene un token por identidad antes de medir. Los tokens duran 15 min, más que cualquiera de las dos corridas.
- **Un solo grupo de 30 hilos para todas las fases.** Un `JSR223Timer` reparte turnos regulares globales de 1000/tasa ms, según la fase vigente, y cada muestra se etiqueta con la fase en la que salió.
  - Se probó primero `Constant Throughput Timer` con un grupo por fase. Al iniciar cada grupo, la primera muestra de cada hilo salía sin espera: 30 solicitudes en el mismo segundo y luego una pausa compensatoria.
  - También se probó `Precise Throughput Timer`. Calcula su calendario desde el inicio de la prueba y suelta en ráfaga las llegadas "atrasadas" de los grupos posteriores.
  - Con el programador global, la distribución por segundo coincide con la tasa de cada fase. Si el servicio no sostiene la tasa, los turnos se atrasan sin recuperarse en ráfaga, y la tasa lograda baja.
- **Aserción por identidad.** Cada respuesta debe traer la jornada de su fila CSV y exactamente sus 10 despachos con su secuencia. Un `200` con datos ajenos se registra como fallo de negocio (`JORNADA_INCORRECTA`, `RUTA_INCORRECTA`). Se comprobó cruzando las filas del CSV: todas las muestras fallaron como se esperaba.
- **Fases.** Se configuran con la propiedad `fases=ETIQUETA:segundos:tasa_por_s,...`. Si se cambian, hay que ajustar `duracionTotal`.

En PERF-02 la aserción exige, en cada respuesta:

- `totalElementos=10000` y `totalPaginas=100`.
- Exactamente 100 filas, sin duplicados.
- Los IDs esperados de la página, en su orden: `TOTAL_ELEMENTOS`, `TOTAL_PAGINAS`, `PAGINA_INCOMPLETA`, `IDS_DUPLICADOS`, `IDS_INCORRECTOS`.

Se comprobó cruzando las páginas del CSV: todas las muestras fallaron con `IDS_INCORRECTOS`. Usa 10 hilos y la sesión de un solo gestor, obtenida en `SETUP-sesion`.

| Caso | Fases | Objetivo p95 |
|---|---|---|
| PERF-02 | `COLA-CALENTAMIENTO` 5/s × 60 s (excluida) · `COLA-CARGA` 5/s × 300 s, alternando las páginas 1, 50 y 100 | < 250 ms |
| PERF-05 | `RUTA-CALENTAMIENTO` 3/s × 60 s (excluida) · `RUTA-CARGA` 3/s × 300 s | < 500 ms |
| PERF-06 | `RUTA-CALENTAMIENTO` 3/s × 60 s (excluida) · `RUTA-BASE` 3/s × 120 s · `RUTA-PICO` 10/s × 30 s · `RUTA-RECUPERACION` 3/s × 60 s | base < 500 ms · pico < 1000 ms · últimos 30 s de recuperación < 500 ms |

Otras propiedades (`-J`): `esquema`, `host`, `puerto` (18080), `seguridad`, `hilos` (30), `rampa` (30 s), `tiempoConexion` (5000 ms), `tiempoRespuesta` (10000 ms) y `rutas`.

## Análisis

`analizar_jtl.py` aplica el criterio común del plan por fase:

- La tasa lograda debe ser al menos el 95 % de la objetivo.
- Los errores inesperados deben ser menos del 1 %.
- Ninguna respuesta exitosa puede traer datos incorrectos.
- El p95 de `elapsed` se calcula sobre todas las muestras de la fase y debe quedar por debajo del umbral.

Los errores se separan en negocio, `429`, `5xx`, otros `4xx` y red o tiempo agotado. El intervalo `ETIQUETA@ultimosN` filtra los últimos N segundos de una fase.
