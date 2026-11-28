# Cambios de RabbitMQ en las integraciones internas

Este documento describe qué cambió al añadir RabbitMQ al backend, cómo era el flujo antes y cómo queda ahora. Es memoria de la entrega, no un contrato: el contrato canónico vive en `G2-Despacho-Specs/integraciones/api-contract.md` §3.6.

## Ramas

- Backend: `feature/rabbitmq-mensajeria-interna`, creada desde `develop`.
- Especificaciones: `docs/rabbitmq-mensajeria-interna`, creada desde `main` (convención de Specs).

No se hicieron commits ni PR: los cambios quedan en el árbol de trabajo para revisión.

## Cómo era antes

Gestión y Operación se comunicaban por HTTP interno. Gestión registraba el hecho confirmado en su outbox (`eventos_salientes`) y un scheduler lo entregaba:

```text
Gestión (eventos_salientes)
  ├─ destino VENTAS -------------------> POST /api/v1/integraciones/despachos/eventos (Ventas)
  ├─ destino OPERACION ----------------> POST /internal/v1/proyecciones/despachos/{id}
  └─ destino OPERACION_LIBERACION -----> POST /internal/v1/capacidad/reservas/{id}/liberacion

Gestión (liberaciones_capacidad) -----> POST /internal/v1/capacidad/reservas/{id}/liberacion
```

El repartidor registraba entrega, fallo e inicio de traslado con un `POST` síncrono e idempotente; la transición de campo ya se notificaba a Gestión desde Operación por REST interno. Las credenciales entre servicios eran `client_credentials` y el webhook a Ventas conservaba su `idEvento` y sus reintentos.

## Cómo queda ahora

RabbitMQ se suma como transporte **opcional** de los dos eventos internos hacia Operación. La decisión la toma `RABBITMQ_HABILITADO`:

```text
Con RABBITMQ_HABILITADO=false (por defecto, y en Render hoy):
  igual que antes, por HTTP interno.

Con RABBITMQ_HABILITADO=true:
  Gestión (eventos_salientes / liberaciones_capacidad)
    └─ PublicadorInternoRabbit (confirmación del broker)
         ├─ despacho.capacidad  -> operacion.liberacion.{idReserva} -> operacion.liberaciones
         └─ despacho.estado     -> despacho.proyeccion              -> despacho.eventos.estado
              Operación: ack manual + eventos_procesados(eventId) -> GestorJornadas
              Fallo definitivo -> despacho.dlx -> *.dlq

  Ventas sigue por webhook HTTPS. Los comandos de campo siguen siendo REST síncrono.
```

El sobre del mensaje lleva `eventId`, `tipo`, `version`, `claveIdempotencia`, `ocurridoEn` y `datos`. El productor solo marca `ENVIADO` el registro del outbox cuando el broker confirma; si no confirma, aplica el backoff existente. El consumidor inserta `eventId` antes de ejecutar y descarta reentregas; si la operación de dominio falla, borra el registro para permitir el reintento y, agotado el procesamiento, el mensaje va a la DLQ.

## Cambios por archivo

### Backend

| Archivo | Cambio |
|---|---|
| `compose.yaml` | Servicio `rabbitmq:3-management` con healthcheck, volumen, puertos 5672/15672, variables `RABBITMQ_*` y `depends_on` saludable en Gestión y Operación. |
| `.env.example` (raíz y dos servicios) | Variables de conexión, flag de habilitación y flag de salud del broker. Sin credenciales reales. |
| `servicio-gestion-despachos/pom.xml` | `spring-boot-starter-amqp`, `spring-boot-starter-amqp-test` y `testcontainers-rabbitmq`. |
| `servicio-operacion-reparto/pom.xml` | Las mismas tres dependencias. |
| `application.yaml` de ambos servicios | `spring.rabbitmq.*`, `despacho.rabbitmq.habilitado` y `management.health.rabbit.enabled=false` por defecto. |
| `ConfiguracionRabbitMQ.java` (Gestión) | Exchanges `despacho.capacidad` y `despacho.estado`, bean `PublicadorInterno`. |
| `MensajeInterno.java`, `PublicadorInterno.java`, `PublicadorInternoRabbit.java` (Gestión) | Sobre, contrato del publicador y publicación con `waitForConfirmsOrDie`. |
| `EntregaEventosProgramacion.java` | Con publicador activo, los eventos internos del outbox se publican en lugar de llamar por HTTP; Ventas NO se toca. Sin publicador, el código HTTP original sigue igual. |
| `LiberadorCapacidadRabbit.java` y `ConfiguracionRecepcion.java` | Adaptador alternativo de liberación; se inyecta solo cuando hay publicador. |
| `ConfiguracionRabbitMQ.java` (Operación) | Colas, DLX `despacho.dlx`, DLQ y bindings. |
| `MensajeInterno.java`, `ProcesadorMensajesInternos.java` (Operación) | Sobre, deduplicación por `eventId` y despacho a `GestorJornadas.liberar` / `proyectar`. |
| `ConsumidorLiberacionesCapacidad.java`, `ConsumidorEstadoDespacho.java` | `@RabbitListener` con `ack` manual y `nack` a DLQ. |
| `V8__eventos_procesados.sql` (Operación) | Tabla de idempotencia del consumidor. |
| Pruebas nuevas | `PublicacionInternaRabbitmqTests` y `ConsumidorMensajesInternosTests`, con PostgreSQL y RabbitMQ desechables. Dos pruebas de los esquemas heredados fuerzan Flyway apagado para no aplicar migraciones sobre ese fixture. |
| `README.md`, `docs/despliegue.md` | Arranque con seis contenedores, columna de RabbitMQ, V13/V8, variables, flag y rollback. |

### Especificaciones

| Archivo | Cambio |
|---|---|
| `integraciones/api-contract.md` | Nueva §3.6 con exchanges, routing keys, sobre, idempotencia, DLQ y fallback HTTP. Se reutilizan `idEvento`/`Idempotency-Key` como nombres canónicos. |
| `README.md` | El índice del contrato externo menciona la §3.6. |
| `arquitectura/c4-contenedores.md` | La tabla de comunicaciones añade una fila para los eventos internos por RabbitMQ, sin borrar las filas REST ni la del webhook. |

## Lo que NO cambió

- **Ventas y Postventa:** el webhook HTTPS con outbox, el `idEvento` y el endpoint de reenvío siguen iguales. RabbitMQ no publica ni duplica ese evento.
- **Operaciones de campo:** entrega, fallo e inicio de traslado siguen respondiendo `200` con `DetalleDespacho` y ejecutándose de forma síncrona e idempotente, conforme a `c4-contenedores.md` §4.
- **Productos (G6), Seguridad (G7) y Gateway:** sin cambios.
- **Contratos REST:** las rutas `/internal/v1/**` existen y se mantienen como fallback; `ControladorCapacidadInterna` no se modificó.
- **Nota pendiente de Specs sobre eventos de usuario de Seguridad:** sigue pendiente porque pertenece a otra integración, no a este cambio. Cerrarla con el contrato G2 sería incorrecto.

## Verificación ejecutada

| Comando | Resultado |
|---|---|
| `SPRING_FLYWAY_ENABLED=true ./mvnw clean verify` en `servicio-gestion-despachos` | 321 pruebas, 0 fallos. |
| `SPRING_FLYWAY_ENABLED=true ./mvnw clean verify` en `servicio-operacion-reparto` | 149 pruebas, 0 fallos. |
| `./mvnw clean verify` en `api-gateway` | 40 pruebas, 0 fallos. |
| `./mvnw spotless:apply` y `spotless:check` en los dos servicios modificados | Correcto. |
| `docker compose config -q` | Correcto. |

`SPRING_FLYWAY_ENABLED=true` es necesario en esta máquina porque el `.env` local de Gestión desactiva Flyway para apuntar a un esquema persistente; las pruebas usaron siempre PostgreSQL desechable vía Testcontainers, nunca Supabase ni la base de trabajo. No se desplegó en producción.

## Cómo probarlo en local

1. `docker compose up -d --build` con `RABBITMQ_HABILITADO=false`: debe funcionar todo igual que antes.
2. Poner `RABBITMQ_HABILITADO=true` en el `.env` raíz y recrear Gestión y Operación:
   `docker compose up -d --build servicio-gestion-despachos servicio-operacion-reparto`.
3. Abrir `http://localhost:15672`, comprobar los exchanges `despacho.capacidad`, `despacho.estado` y `despacho.dlx`, y las colas `operacion.liberaciones`, `despacho.eventos.estado` y sus DLQ.
4. Registrar una entrega o una recepción que genere liberación y verificar que el evento llega a la cola, se consume una sola vez y no queda en la DLQ.
5. Volver al flujo anterior con `RABBITMQ_HABILITADO=false` y recreando los servicios.

## Desviaciones respecto al plan original

- No se creó `PublicadorEstadoDespacho.java`: la proyección ya sale del outbox existente y se publica con el mismo publicador, sin una segunda bandeja.
- No se publica el evento de campo hacia Gestión: la transición ya se comunica por REST interno y no hay consumidor contratado para esa cola. Añadirla sería una cola sin consumidor.
- No hay migración nueva en Gestión: `eventos_salientes` ya es el outbox. La única migración nueva es V8 en Operación.
- No se cerró la nota pendiente de eventos de usuario de Seguridad, por el motivo explicado arriba.
