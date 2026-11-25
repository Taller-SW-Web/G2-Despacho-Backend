# Pruebas de integración del hito 5 (Java)

Verificador Java 25 LTS + JUnit Jupiter con `java.net.http.HttpClient`. Levanta `pruebas/compose.f02.yaml` y no invoca los scripts Python.

## Ejecutar

Requisitos: Docker en ejecución, puertos `18080` y `18090` libres y los tres JAR construidos.

```powershell
# Desde la raíz del backend, una vez por cambio de código
cd servicio-gestion-despachos; .\mvnw.cmd clean verify; cd ..
cd servicio-operacion-reparto; .\mvnw.cmd clean verify; cd ..
cd api-gateway; .\mvnw.cmd clean verify; cd ..

# Pruebas de integración
cd pruebas/integracion-java
.\mvnw.cmd clean test "-Dcommit=$(git rev-parse --short HEAD)"
```

En Linux o WSL se usa `./mvnw` en lugar de `.\mvnw.cmd`.

Salidas en `target/`:

- `surefire-reports/`: informe JUnit (XML y TXT).
- `informes/int-02-int-08.json`: IDs sintéticos y cada aserción por caso. No contiene tokens.
- `informes/servicios-compose.log`: solo se genera si algo falla.

## Escenario y servicios

| Componente | Tipo |
|---|---|
| Gateway, Gestión, Operación | Reales, a partir de los JAR del commit |
| PostgreSQL | Real y desechable (`tmpfs`) |
| Seguridad | Doble: emisor temporal `seguridad-local` |

Cada clase usa un proyecto Compose con nombre único, que se retira al terminar. La preparación está en `EscenarioRutasF02`, migrada de `verificar_f02_rutas.py` (líneas 130-170):

- Una zona, un distrito y una tarifa insertados por SQL en `despacho_gestion`.
- Repartidor A (perfil `repartidor`) y repartidor B (perfil `sin-rol`) insertados por SQL en `despacho_operacion`, ambos `VINCULADO` y con furgoneta.
- Una jornada activa para cada uno, creada por Gateway.
- Despachos simulados de 2 kg y 0,01 m³: uno asignado a B y dos a A. El último de A es el despacho bajo prueba.
- La proyección hacia Operación se espera con un máximo de 20 s.

## Correspondencia con el verificador Python

| Comprobación Python (`verificar_f02_publicado.py`) | Java | Caso |
|---|---|---|
| `salud_gateway`, `cola_sin_token`, `ruta_sin_token` | `Int02.rechazaSinToken` | INT-02 |
| `cola_autorizada` | `Int02.colaAutorizada` | INT-02 |
| `ruta_gestion`, `ruta_propia` | `Int02.mismaJornadaYSecuencia` | INT-02 |
| `mismo_despacho_jornada_secuencia_y_ocupacion` | `Int02.mismaJornadaYSecuencia` y `Int02.ocupacionCoherente` | INT-02 |
| `ocupacion_jornada` | `Int02.ocupacionCoherente` | INT-02 |
| `despacho_ajeno` (solo exigía `404`) | `Int08.ajenoResponde404SinDetalle` | INT-08 |

Aserciones añadidas respecto de Python:

- **INT-02:** el despacho asignado ya no aparece en la cola de pendientes, y la ocupación de la jornada es igual a la suma de la ruta de A.
- **INT-08:** controles de que A ve su detalle y de que B ve el suyo. El cuerpo del `404` no contiene el destinatario, el teléfono, la dirección ni el código de rastreo, que se leen del detalle de A. Tampoco incluye campos del detalle. Un ID inexistente responde igual que uno ajeno.

Fuera de alcance: `verificar_anulacion_fallido` (anulación de un fallido, recepción y cierre F-04). Sigue cubierto por el script Python.
