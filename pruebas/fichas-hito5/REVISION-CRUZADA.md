# Revisión cruzada pendiente · hito 5

La revisión técnica automatizada no sustituye la aceptación de Persona A y Persona B.
Ambas personas deben registrar nombre, fecha y observaciones sobre una corrida concreta.

## Persona A revisa B

- [ ] INT-01: 14 comprobaciones Java convergentes con Python sobre datos recreados.
- [ ] Rechazos sin cambios, reserva original liberada y repetición sin duplicados.
- [ ] PERF-03: catálogo simulado declarado, tarifa y cálculo exactos, conteos 31→181.
- [ ] PERF-04: control separado, CSV sin reciclado, 600 muestras medidas y reconciliación de reservas.
- [ ] INT-10 figura Bloqueado; no se presenta un doble como integración G6/G7 real.

Revisor / fecha / corrida / observaciones: **Pendiente**.

## Persona B revisa A

- [ ] INT-02/08: rutas e identidad correctas, `404` sin datos privados.
- [ ] INT-09: transferencia real confirmada antes del corte y recuperación sin segunda reserva.
- [ ] PERF-02: 10.000 pendientes y páginas 1, 50 y 100 correctas.
- [ ] PERF-05/06: mismas 30 rutas, aserciones por identidad y ventanas nominales.
- [ ] PERF-06: tasas y p95 por fase, incluidos los últimos 30 segundos.

Revisor / fecha / corrida / observaciones: **Pendiente**.

## Evidencia que debe acompañar la revisión

- [Cierre y resultados](./CIERRE-HITO5.md).
- [Resúmenes, gráficas e informes versionados](./evidencia/revalidacion-java21/).
- Paquete completo local en `pruebas/rendimiento/resultados/entrega-hito5-java21.tar.gz`
  y su archivo `.sha256`, pendientes de adjuntar al PR o publicar en almacenamiento autorizado.
- Dentro del paquete: JTL, carpetas HTML completas, informes Surefire, entradas de carga,
  fuentes del commit medido, trazabilidad y logs de intentos anteriores.

## Publicación por el equipo

1. Comprobar el SHA-256 y abrir los HTML localmente.
2. Adjuntar el paquete a un destino autorizado; registrar el enlace compartido en el PR.
3. Completar ambas revisiones y mantener explícitos los bloqueos externos.
4. Publicar `test/hito5-integracion` y abrir PR a `develop`. No integrar sin aprobación.

No se realizó push, publicación remota ni merge a `develop` durante esta sesión.
