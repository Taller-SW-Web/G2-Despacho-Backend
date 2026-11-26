# INT-02 · El despacho asignado aparece igual en cola, ruta administrativa y Mi Ruta

| Campo | Valor |
|---|---|
| **Caso / responsable** | INT-02 · Persona A |
| **Fecha y commit** | 2026-11-26 03:10 (America/Lima) · `9372386`. El código de la suite es idéntico en `47a8c63`. Primera corrida en verde: 2026-11-25 sobre `06715db`. |
| **Comando** | `pruebas/integracion-java`: `mvnw clean test -Dcommit=<sha>`, con JDK 25 y Docker. Ver el [README](../integracion-java/README.md). |
| **Entorno** | `compose.f02.yaml` con un nombre de proyecto único: Gateway, Gestión y Operación reales (JAR del commit) y PostgreSQL desechable. Seguridad es el doble `seguridad-local`. El entorno se retira al terminar. |
| **Datos sintéticos** | Zona `4c61efda-…`. Repartidor A (perfil `repartidor`) con la jornada `0da36989-…` y los despachos `9c2b29b4-…` y **`3468b58f-5d3d-4816-8041-05da3e34dd6f`** (bajo prueba). Repartidor B (perfil `sin-rol`) con la jornada `0bace995-…` y el despacho `fd52d106-…`. Todos de 2 kg y 0,01 m³. IDs completos en [`int-02-int-08.json`](evidencia/int-02-int-08.json). |
| **Migración** | Comprobaciones de `verificar_f02_publicado.py` y escenario de `verificar_f02_rutas.py`, con la correspondencia documentada en el README. La comparación Java ↔ Python sobre datos recreados se hizo en `47a8c63` y **converge**: ver [COMPARACION-PYTHON](COMPARACION-PYTHON.md). |
| **Aserciones (18)** | Gateway sano; cola y Mi Ruta sin token → `401`. La cola autorizada tiene su estructura y **ya no lista el despacho asignado**. La ruta de Gestión tiene la jornada `ACTIVA` y `versionRuta`, y Mi Ruta la jornada propia. El despacho aparece **una vez** en cada ruta, como `ASIGNADO` en ambas, con `posicion` = `secuenciaRuta` y el repartidor de la jornada. En peso, volumen y paquetes: límite > 0, ocupación ≥ carga > 0, límite − ocupación = remanente, y **ocupación = suma de la ruta de A**. |
| **Resultado observado** | Las 18 aserciones correctas. La proyección convergió en 678 ms (máximo admitido: 20 s). Surefire: 4 tests de `Int02`, 0 fallos. |
| **Resultado** | **Aprobado** |
| **Evidencia** | [`RutasRepartidorF02Test.java`](../integracion-java/src/test/java/com/modulodespacho/pruebas/integracion/f02/RutasRepartidorF02Test.java) · [informe JSON](evidencia/int-02-int-08.json) · [Surefire](evidencia/TEST-com.modulodespacho.pruebas.integracion.f02.RutasRepartidorF02Test$Int02.xml) |
