# Fichas del hito 5 · Persona A

Resultados de los casos de integración y rendimiento asignados a Persona A en el plan de pruebas del hito 5. Todos se ejecutaron el 2026-11-26 en el entorno local descrito en cada ficha.

| Caso | Qué verifica | Resultado | Ficha |
|---|---|---|---|
| **INT-02** | El despacho asignado aparece una vez, igual, en la ruta administrativa y en Mi Ruta, y sale de la cola | **Aprobado** · 18/18 aserciones | [INT-02](INT-02.md) |
| **INT-08** | Un repartidor recibe `404` sin datos al pedir un despacho ajeno | **Aprobado** · 9/9 aserciones | [INT-08](INT-08.md) |
| **INT-09** | La reasignación se recupera sin duplicar reservas cuando se pierde la respuesta de Operación | **Aprobado** · 17/17 aserciones | [INT-09](INT-09.md) |
| **PERF-02** | Cola de 10.000 pendientes a 5/s durante 5 min | **Aprobado** · p95 20 ms (< 250) | [PERF-02](PERF-02.md) |
| **PERF-05** | Mi Ruta con 30 identidades a 3/s durante 5 min | **Aprobado** · p95 23 ms (< 500) | [PERF-05](PERF-05.md) |
| **PERF-06** | Mi Ruta: base 3/s, pico 10/s y recuperación 3/s | **Aprobado** · p95 22 / 8 / 10 ms | [PERF-06](PERF-06.md) |

La comparación Java ↔ Python de INT-02 e INT-08 sobre datos recreados **converge**: ver [COMPARACION-PYTHON](COMPARACION-PYTHON.md).

Criterio común de rendimiento, escrito antes de ejecutar:

- Se compara el **p95** del tiempo total de respuesta HTTP (`elapsed`).
- La tasa lograda debe ser al menos el 95 % de la objetivo.
- Los errores inesperados deben ser menos del 1 %.
- Las validaciones de negocio deben cumplirse en el 100 % de las respuestas exitosas.

## Alcance y límites

- **Entorno.** Todo es local con Docker Compose. Seguridad es el doble `seguridad-local`; no intervienen G7, G6 ni G5 reales. Los servicios corren en Java 25. JMeter 5.6.3 corre sobre Java 21, porque su Groovy no admite versiones posteriores.
- **La carga del plan es liviana para este entorno.** Los tiempos quedan un orden de magnitud por debajo de los umbrales. Los casos demuestran que se cumplen las metas y que no se mezclan identidades ni páginas bajo carga y pico, pero **no** dónde empieza la degradación; eso sería una prueba de estrés, fuera de alcance.
- **Commits.** Los `commit.txt` de rendimiento apuntan al commit anterior al medido, porque el código se commiteó justo después de cada corrida y sin cambios. Cada ficha indica el commit real.
- **Evidencia completa.** Los JTL, los informes HTML de JMeter y `recursos.csv` quedan en `pruebas/rendimiento/resultados/`, fuera de Git. Esta carpeta guarda los resúmenes, los informes de integración y las gráficas.

## Pendiente

- **Revisión cruzada.** Persona B revisa estas fichas y Persona A revisa el reporte de Persona B.

## Gráficas para la presentación

[PERF-02](graficas/PERF-02.png) · [PERF-05](graficas/PERF-05.png) · [PERF-06](graficas/PERF-06.png). Cada una muestra la tasa real y el p95 por ventana de 5 s, en dos gráficas con el mismo eje de tiempo, con las fases y los umbrales marcados. Las genera [`grafica_fases.py`](../rendimiento/grafica_fases.py) a partir del JTL.
