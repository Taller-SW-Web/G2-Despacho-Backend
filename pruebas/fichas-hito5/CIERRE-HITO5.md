# Verificación del hito 5 · integración A+B

## Entorno y trazabilidad

- Código medido: `4a81f6d` en `test/hito5-persona-b`, derivada de `test/hito5-integracion`.
- Suite Java de cierre repetida sobre `c9363fe`: 22/22 aprobadas, sin fallos, errores ni omitidas.
- Verificador y generador en Java 21.0.13.1 (Corretto); Apache JMeter 5.6.3. Servicios en imágenes Temurin 21.
- Linux 6.18.65, AMD Ryzen 7 7735HS, 16 CPU disponibles; Docker 29.10.0, memoria disponible de 13,33 GiB.
- Gateway, Gestión, Operación y PostgreSQL/PostGIS reales locales. Seguridad temporal; Productos simulados únicamente para cotización local.
- RabbitMQ deshabilitado en este Compose. No acredita G6/G7/G5 reales, despliegue ni aceptación humana.
- Cada corrida registra el commit antes/después y los SHA-256 de los JAR. Las cargas se ejecutan secuencialmente.

## Integración y regresión

| Comprobación | Resultado |
|---|---|
| Gestión `clean verify` | 321 aprobadas, cero omitidas |
| Operación `clean verify` | 149 aprobadas, cero omitidas |
| Gateway `clean verify` | 40 aprobadas, cero omitidas |
| Integración A+B | 22 aprobadas: INT-01 (14), INT-02/08 (7), INT-09 (1) |
| INT-01 Python sobre entorno recreado | 14 comprobaciones aprobadas, convergentes con Java |
| F-02 Python sobre entorno recreado | `F02_RUTAS_VERIFICADO` y `F02_ANULACION_FALLIDO_VERIFICADO`; converge con INT-02/08 y conserva la cobertura F-04 no migrada |
| Regresiones del analizador/configuración JMeter | 8 aprobadas |
| Seguridad y Ventas temporales | Suites Java standalone ejecutadas y aprobadas |
| Regresiones Python de verificadores existentes | 29 aprobadas |

INT-09: el intermediario Java ahora corre dentro de la red Compose y su control se publica únicamente en loopback.
Esto elimina el callback a `host.docker.internal` que fallaba en Linux. Se conserva el corte después de `200 TRANSFERIDA`,
la recuperación de la misma operación y todas las aserciones de rutas, reservas y capacidad.

## Rendimiento

Percentiles por rango más cercano de `elapsed`, sobre todas las muestras del intervalo medido.
La tasa se calcula usando ventanas nominales, no la distancia entre primera y última respuesta.
Sesiones, control previo y calentamiento quedan fuera de la medición.

| Caso | Muestras | Duración | Tasa objetivo / lograda | p50 / p95 / p99 ms | Errores | Resultado |
|---|---|---|---|---|---|---|
| PERF-02 | 1498 | 300 s | 5 / 4,993 por s | 39 / 48 / 52 | 0 | Aprobado |
| PERF-03 | 150 | 300 s | 0,5 / 0,5 por s | 24 / 30 / 34 | 0 | Aprobado |
| PERF-04 | 600 | 300 s | 2 / 2 por s | 24 / 29 / 32 | 0 | Aprobado |
| PERF-05 | 899 | 300 s | 3 / 2,997 por s | 11 / 13 / 18 | 0 | Aprobado |
| PERF-06 base | 360 | 120 s | 3 / 3 por s | 8 / 11 / 12 | 0 | Aprobado |
| PERF-06 pico | 300 | 30 s | 10 / 10 por s | 6 / 8 / 9 | 0 | Aprobado |
| PERF-06 recuperación completa | 179 | 60 s | 3 / 2,983 por s | 8 / 10 / 11 | 0 | Aprobado |
| PERF-06 últimos 30 s | 89 | 30 s | 3 / 2,967 por s | 9 / 10 / 11 | 0 | Aprobado |

- PERF-02: lote de 10.000 pendientes; páginas 1, 50 y 100 y sus IDs validados en cada muestra.
- PERF-03: tarifa S/10 base, 3 kg incluidos, S/2 adicional por kg y factor volumétrico 1,25.
  `POL-NEG-M` ×5 y `ZAP-RUN-42` ×1: 2,65 kg, 0,021 m³ y S/10. Cuota predeterminada 60/min por cliente/IP;
  no sobrescrita en el Compose. Conteos reales 31 antes y 181 después: 150 instantáneas medidas coherentes.
- PERF-04: 800 despachos preparados entre 20 jornadas; control separado y CSV no reciclable.
  Tras control, calentamiento y medición: 721 despachos asignados y 721 reservas activas únicas, sin jornadas excedidas.
- CPU/RAM completas en cada `recursos.csv`, incluidas preparación de sesiones y calentamiento; no son medias exclusivas de la fase medida.

Todos los casos y fases alcanzan al menos 95 % de la tasa prevista, menos del 1 % de errores inesperados
y ninguna respuesta exitosa con datos incorrectos. El p95 es estrictamente menor al límite en cada fase exigida.
Esta carga piloto no busca el punto de saturación; el pico no produjo degradación apreciable.

## Corridas y evidencia reproducible

| Caso | Carpeta bajo `pruebas/rendimiento/resultados/` |
|---|---|
| PERF-02 | `PERF-02-20261127-203611` |
| PERF-03 | `PERF-03-20261127-201850` |
| PERF-04 | `PERF-04-20261127-202700` |
| PERF-05 | `PERF-05-20261127-204319` |
| PERF-06 | `PERF-06-20261127-204927` |

Cada carpeta conserva JTL, HTML completo, `ventanas.json`, commit previo/final, SHA-256 de JAR,
CPU/RAM y resumen. B añade el control funcional, ledger de éxitos y reconciliación; PERF-03 añade
los conteos tomados antes/después. Se adjuntan resúmenes, gráficas y XML de integración en
[evidencia/revalidacion-java21](./evidencia/revalidacion-java21/).
Los paquetes con JTL/HTML completos permanecen fuera de Git y requieren adjuntarse al PR o a
un almacenamiento autorizado: los enlaces locales no significan que ya se hayan publicado.
Paquete consolidado: `pruebas/rendimiento/resultados/entrega-hito5-java21.tar.gz`;
verificación: `sha256sum -c entrega-hito5-java21.tar.gz.sha256` desde esa carpeta.
Incluye las cinco corridas completas, sus fichas, entradas sin credenciales, fuentes medidas,
informes Surefire, comparación Python y logs de los primeros intentos.
Tamaño, checksum y contenido inventariado en [PAQUETE-EVIDENCIA](./PAQUETE-EVIDENCIA.md).

## Recursos de B

Medias y máximos de toda la corrida, incluidos setup/control y calentamiento.

| Caso / contenedor | CPU media / máxima % | RAM máxima MiB |
|---|---|---|
| PERF-03 Gateway | 1,50 / 6,07 | 433,8 |
| PERF-03 Gestión | 3,04 / 24,00 | 596,2 |
| PERF-03 Operación | 0,25 / 0,47 | 417,6 |
| PERF-03 PostgreSQL | 7,23 / 41,76 | 328,2 |
| PERF-04 Gateway | 2,83 / 11,26 | 389,5 |
| PERF-04 Gestión | 7,90 / 44,74 | 687,7 |
| PERF-04 Operación | 4,85 / 22,43 | 494,2 |
| PERF-04 PostgreSQL | 8,51 / 28,20 | 314,1 |

## Intentos no concluyentes y límites

- Primera corrida INT-09 en Linux: asignación inicial 503 antes de armar el corte. Se conservan logs e informes del intento.
- Primera corrida PERF-03: controlador de setup repetía autenticación y agotó 900 segundos sin aplicar la carga de cotización.
  No concluyente por configuración del generador; no se usa su latencia como medida del endpoint. Su JTL se conserva.
- Se corrigió el controlador y se ejecutó una prueba corta: una autenticación y nueve peticiones de negocio, finalización acotada.
  Esa prueba corta tampoco acredita el objetivo de cinco minutos.
- Los resultados anteriores de A sobre Java 25 se conservan como históricos; la tabla superior usa exclusivamente las nuevas corridas.

## Dependencias externas y revisión

- INT-10 bloqueado: faltan origen QA autorizado G6, credencial/grant G7, perfiles vigentes de los tres SKU y evidencia correlacionada de emisión/uso del token.
- INT-11/G5 real permanece fuera de alcance como pendiente crítico.
- La revisión técnica de código de A encontró y corrigió ventanas, señal de error y trazabilidad.
  La revisión humana cruzada A↔B sigue pendiente y no se considera aprobada automáticamente.
  Se deja una [lista de revisión cruzada](./REVISION-CRUZADA.md) para registrar ambas aceptaciones.
- Publicación de paquetes completos y PR a `develop` a cargo del equipo; sin push ni merge a `develop` por el agente.

## Estado de cierre

La implementación y verificación local del alcance ejecutable están terminadas. No equivale al cierre de INT-10
ni a una aprobación de personas A/B. El equipo debe revisar los paquetes, adjuntarlos a un destino compartido
y abrir el PR de `test/hito5-integracion` a `develop`. Los commits posteriores a `4a81f6d` que cierran
esta entrega agregan evidencia, documentación, permisos de ejecución y normalización del classpath
del intermediario para el separador del host. No alteran los servicios ni los planes o scripts de carga medidos.
La suite Java se repitió al cerrar este ajuste: 22/22 aprobadas. Windows no se ejecutó en esta sesión.
