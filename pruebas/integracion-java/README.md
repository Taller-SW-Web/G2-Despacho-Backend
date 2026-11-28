# Pruebas de integración del hito 5 (Java)

Verificador Java 21 + JUnit Jupiter con `java.net.http.HttpClient`. Levanta `pruebas/compose.f02.yaml` y no invoca los scripts Python.

## Ejecutar

Requisitos: Docker en ejecución, JDK 21 en `JAVA_HOME`, puertos `18080` y `18090` libres, y los tres JAR construidos. INT-09 ejecuta el intermediario dentro de Compose; su control se publica en un puerto aleatorio de `127.0.0.1`. No publica Operación ni requiere callbacks al host.

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
- `informes/int-02-int-08.json` e `informes/int-09.json`: IDs sintéticos y cada aserción por caso. No contienen tokens.
- `informes/servicios-compose-<proyecto>.log`: registros separados por escenario, sin sobrescribir los de otra clase. INT-02 e INT-08 lo generan solo si algo falla; INT-01 e INT-09 siempre, como evidencia.

Para ejecutar un solo caso: `-Dtest=RutasRepartidorF02Test` (INT-02 e INT-08) o `-Dtest=ReasignacionRecuperacionF02Test` (INT-09).

Persona B añade `-Dtest=RecepcionLiberacionF04Test` (INT-01): 14 comprobaciones, reserva original
de 2 kg / 0,01 m³ / un paquete y persistencia antes/después. No invoca Python. La comparación
se ejecuta por separado recreando los datos. Resultados y límites en [CIERRE-HITO5](../fichas-hito5/CIERRE-HITO5.md).

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

## INT-09 · Reasignación con respuesta perdida

`ReasignacionRecuperacionF02Test` no tiene equivalente en Python. Reutiliza el escenario anterior y reasigna a B el despacho bajo prueba de A.

**Intermediario.** `IntermediarioCorte` es el mismo servidor HTTP Java, ejecutado en un contenedor
de la red desechable. Una superposición Compose hace que Gestión llame a `intermediario:19099`;
el intermediario llama a Operación por su DNS interno. El control se publica solo en loopback,
sin depender de `host.docker.internal` ni del firewall del anfitrión. Se montan en solo lectura
las clases compiladas del módulo y las dependencias Maven, no credenciales ni configuración Maven.
Reenvía todo sin cambios, salvo la primera transferencia después de armarlo: la entrega a Operación,
espera su respuesta completa y cierra la conexión hacia Gestión sin responder.

**Comprobaciones:**

1. La primera solicitud recibe `503`.
2. Operación respondió `200 TRANSFERIDA` antes del corte. La transferencia ya es durable: la reserva original queda `LIBERADA` y la nueva de B `ACTIVA`.
3. El reintento con la misma identidad, clave, `If-Match` y cuerpo devuelve `200` con la reserva confirmada. Una repetición más devuelve la misma respuesta.
4. Existe una única reserva activa del despacho, en B, y Operación guarda una sola transferencia por clave.
5. Al converger, el despacho aparece solo en la ruta administrativa y en Mi Ruta de B. La carga pasa de la ocupación de A a la de B.

El contenedor y su puerto de control se retiran junto con el proyecto Compose. No es un servicio
productivo ni debe desplegarse en QA o producción.

**Limitación.** El ciclo periódico de recuperación de Gestión (500 ms en `compose.f02.yaml`) también entrega los eventos de proyección, así que no se desactiva. Por eso la transferencia pendiente puede reenviarla ese ciclo o el reintento del cliente. El informe registra cuándo se observó el reenvío.
