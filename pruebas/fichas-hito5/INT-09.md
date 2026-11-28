# INT-09 · Reasignación con respuesta perdida entre Gestión y Operación

| Campo | Valor |
|---|---|
| **Caso / responsable** | INT-09 · Persona A |
| **Fecha y commit** | 2026-11-26 03:09 (America/Lima) · `9372386`. El código del caso es idéntico en `47a8c63`. Primera corrida en verde: 2026-11-25 sobre `628e231`. |
| **Comando** | `pruebas/integracion-java`: `mvnw test -Dtest=ReasignacionRecuperacionF02Test`, o la suite completa. |
| **Entorno y dobles** | `compose.f02.yaml` más una superposición del caso: Gestión llama a Operación a través de `IntermediarioCorte`, que corre en el JVM de la prueba (`host.docker.internal`), y Operación se publica en `127.0.0.1:18082`. Gateway, Gestión, Operación y PostgreSQL son reales. Los dobles son `seguridad-local` y el intermediario. |
| **Datos sintéticos** | Escenario de INT-02. El despacho `2b6f8fbb-4619-4b55-bd2a-efbc8004836e`, `ASIGNADO` a A, se reasigna a B, que tiene capacidad suficiente. IDs completos en [`int-09.json`](evidencia/int-09.json). |
| **Entrada** | `POST /api/v1/despachos/{id}/reasignacion` por Gateway con el gestor, `If-Match` con la versión del despacho, `Idempotency-Key` UUID v4 y un motivo. |

## Secuencia observada

| Instante (UTC) | Evento |
|---|---|
| 08:09:51.552 | El intermediario recibe la transferencia de Gestión y la reenvía a Operación. |
| 08:09:51.574 | Operación responde `200 TRANSFERIDA` con la nueva reserva `22b4db80-…`. |
| 08:09:51.577 | El intermediario **corta** la conexión hacia Gestión sin responder, 2,6 ms **después** de la confirmación. |
| — | El cliente recibe `503 DESP_ERROR_OPERACION_NO_DISPONIBLE`. |
| — | En Operación, la reserva original `fe2856b3-…` ya está `LIBERADA` y la nueva, `ACTIVA` en la jornada de B. |
| 08:09:51.805 | Reintento idéntico del cliente: Gestión reenvía la transferencia con la misma clave y Operación devuelve la misma reserva. |
| — | El cliente recibe `200` con la reasignación. Una repetición más devuelve la respuesta idéntica. |

| Lectura | Antes | Después |
|---|---|---|
| Rutas de A (Gestión / Mi Ruta) | 2 despachos, incluido el bajo prueba | 1 despacho, sin el bajo prueba |
| Rutas de B (Gestión / Mi Ruta) | 1 despacho | 2 despachos, incluido el bajo prueba |
| Ocupación de A | 4 kg · 0,02 m³ · 2 paquetes | 2 kg · 0,01 m³ · 1 paquete |
| Ocupación de B | 2 kg · 0,01 m³ · 1 paquete | 4 kg · 0,02 m³ · 2 paquetes |
| Reservas del despacho | 1 `ACTIVA` (A) | original `LIBERADA` + **1 sola `ACTIVA`** (B) |
| Transferencias por clave en Operación | — | 1 |

| Campo | Valor |
|---|---|
| **Aserciones (17)** | Todas correctas: reserva inicial única; `503` recuperable; solo se corta la primera transferencia; Operación confirmó antes del corte; la transferencia es durable tras el corte; los reenvíos devuelven la misma reserva; el reintento da `200` con la reasignación y la reserva confirmada; la repetición es idéntica; reserva original liberada; una sola activa en B; una transferencia por clave; el despacho queda solo en las rutas de B; la capacidad se transfiere en las tres dimensiones. |
| **Observaciones** | El contrato de Gestión §3.2.2 exige un `503` recuperable sin fijar el código; Gestión devolvió `DESP_ERROR_OPERACION_NO_DISPONIBLE`. El ciclo periódico de recuperación de Gestión (500 ms) no se puede desactivar, porque también entrega la proyección. En esta corrida el reenvío ocurrió durante el reintento del cliente. |
| **Resultado** | **Aprobado** |
| **Evidencia** | [`ReasignacionRecuperacionF02Test.java`](../integracion-java/src/test/java/com/modulodespacho/pruebas/integracion/f02/ReasignacionRecuperacionF02Test.java) · [`IntermediarioCorte.java`](../integracion-java/src/test/java/com/modulodespacho/pruebas/integracion/soporte/IntermediarioCorte.java) · [informe JSON](evidencia/int-09.json) · [Surefire](evidencia/TEST-com.modulodespacho.pruebas.integracion.f02.ReasignacionRecuperacionF02Test.xml) |
