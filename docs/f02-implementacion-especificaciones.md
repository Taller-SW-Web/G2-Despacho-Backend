# F-02: recepción, simulación, cola de pendientes y asignación

**Fecha de verificación:** 16/10/2026, America/Lima.

**Rama:** `feature/f02-implementacion-specs`, base `316767f`, cambios en el árbol de trabajo.

**Resultado:** backend de ES-F02-01 a ES-F02-04 implementado en `servicio-gestion-despachos`; compilación, empaquetado y 69 pruebas de Gestión aprobados. Las comprobaciones usan persistencia y JWT reales con proveedores externos simulados por HTTP.

## 1. Fuentes y decisiones aplicadas

Especificaciones revisadas en `G2-Despacho-Specs`, revisión `91db8f8`: [ES-F02-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/91db8f8/especificaciones/ES-F02-01-RecepcionSolicitudDespacho/spec.md), ES-F02-02, ES-F02-03 y ES-F02-04, junto con sus planes y tareas [cite: 1].

Prevalecen los contratos canónicos ante las diferencias de los planes:

- Productos: `POST /api/v1/productos/datos-fisicos/consulta`, con `skus`, peso en kg y dimensiones en cm; no se utiliza la ruta antigua `lote-datos-fisicos` [cite: 2].
- Capacidad: `POST /internal/v1/capacidad/reservas`, con clave UUID v4 en `Idempotency-Key`; compensación mediante `POST /internal/v1/capacidad/reservas/{idReserva}/liberacion`, motivo `COMPENSACION`. No se utiliza un DELETE ni una clave en el cuerpo [cite: 3].
- Sin token o con token no aceptado: `401`, con `WWW-Authenticate: Bearer`. Token aceptado sin autorización: `403`, antes de validar o consultar datos. Esto corrige la referencia a `403` para peticiones anónimas en algunas atómicas [cite: 2].
- La cola consulta mediante SQL parametrizado, aprovechando el patrón JDBC existente de F-04. La entidad canónica y sus escrituras usan JPA con `@Version`. No se busca una reprogramación con `LIKE` ni se deduce de `numeroIntentos`: se identifica la transición histórica estructurada de F-04 [cite: 4].

## 2. Entradas disponibles

| Especificación | Método y ruta | Autorización |
|---|---|---|
| ES-F02-01 | `POST /api/v1/despachos` | Servicio, `sub=modulo-ventas`, audiencia `api-despacho`, scope `despachos:crear`; `Idempotency-Key` UUID v4 obligatorio. |
| ES-F02-02 | `POST /api/v1/despachos/simulaciones` | Acceso humano con `GESTOR_DESPACHO`. |
| ES-F02-03 | `GET /api/v1/despachos/pendientes` | Acceso humano con `GESTOR_DESPACHO`. |
| ES-F02-04 | `POST /api/v1/despachos/{idDespacho}/asignacion` | Acceso humano con `GESTOR_DESPACHO`. |

Los modelos implementados están en `programacion/application/ModelosProgramacion.java`. `idDespacho` mantiene la identidad UUID de la base canónica; `codigoOperativo` contiene el código `DSP-…` o `DSP-SIM-…`. Ambos son identificadores opacos: los consumidores no deben inferir su formato. El pedido externo se conserva como texto, por ejemplo `PED-2026-00891`, sin exigir UUID.

La cola recibe `pagina`, `tamanoPagina`, `idZona`, `fechaDesde`, `fechaHasta` y `tipoIntento`; devuelve la metadata `paginaActual` prevista por ES-F02-03. Los indicadores se calculan sobre todo el universo pendiente, antes de filtros y paginación. `asignable`, `disponibleDesde`, `fechaActualServidor` y `consultadoEn` permiten presentar la condición de fecha sin depender del reloj del navegador; la asignación vuelve a validarla.

## 3. Persistencia y atomicidad

[V3__programacion_despachos.sql](../servicio-gestion-despachos/src/main/resources/db/migration/V3__programacion_despachos.sql) extiende V1/V2:

- Unicidad de pedido, código operativo y posición de ruta por jornada; índice parcial de pendientes.
- Identificadores externos de pedido/repartidor/jornada/reserva como texto; carga decimal sin redondear volúmenes pequeños a cero.
- Historial canónico, claves de idempotencia, intenciones durables de asignación y outbox.
- Despacho, historial y resultado de creación se guardan o revierten juntos. La creación fija un paquete lógico y obtiene los datos físicos exclusivamente del cliente HTTP de Productos de F-02; el stub existente de cotización conserva su consumidor de F-01.

Una repetición de la misma clave y contenido devuelve el código HTTP y cuerpo originales, incluso si cambió después el despacho. Otra clave para el mismo pedido devuelve `200` con el recurso actual, sin volver a consultar Productos. Reutilizar una clave con otro contenido devuelve `409`.

## 4. Asignación recuperable

1. Una transacción local comprueba estado y fecha, registra versión de origen, ejecutor, destino de asignación, carga original y una clave técnica nueva.
2. Fuera de esa transacción, Operación valida jornada, habilitación, capacidad tridimensional y reserva anterior. La carga que recibe es la instantánea durable de la intención.
3. Otra transacción comprueba la versión y el estado de la intención, asigna la siguiente posición de la jornada y escribe despacho, historial, resultado y comunicaciones pendientes. `@Version` protege la escritura; un bloqueo corto por jornada evita repetir posiciones entre despachos distintos.
4. Una respuesta perdida conserva la intención y su clave. El reintento vuelve a solicitar la misma reserva idempotente; no genera una clave nueva para una operación incierta.
5. Un fallo local posterior a reservar inicia una compensación durable. Si la liberación falla, permanece pendiente y se retoma. No se compensa una reserva cuya asignación otra recuperación ya confirmó.

Los HTTP no mantienen transacciones de base de datos abiertas. Las intenciones recuperables se reclaman temporalmente y se reintentan desde el procesador periódico. El GET de pendientes no ejecuta recuperación ni reservas.

La auditoría guarda carga reservada y capacidad remanente confirmada. No inventa una ocupación absoluta que Operación no haya suministrado en su respuesta.

## 5. Comunicaciones posteriores

La asignación real registra `DESPACHO_ASIGNADO` para Ventas y una proyección versionada para Operación en la misma transacción local. La asignación simulada solo registra la proyección interna; nunca un evento comercial.

El procesador entrega fuera de la transacción, con credencial propia por destinatario. Ventas tiene un envío inicial y hasta cinco reintentos con espera creciente, conservando `idEvento`; al agotarlos queda `ENVIO_FALLIDO`. La proyección interna conserva su versión y recuperación continua. Solo un `2xx` acredita entrega; una redirección no se considera éxito. La reclamación del evento tiene un identificador propio para impedir que un intento vencido sobrescriba el resultado de otro.

El reenvío manual transversal de RT-03 y el backend proveedor de capacidad/proyecciones de F-06 pertenecen a sus incrementos respectivos. Este trabajo implementa los consumidores HTTP y el almacenamiento/envío necesarios para las cuatro operaciones de Gestión.

## 6. Configuración de ejecución

Usar Java 21, PostgreSQL exclusivo de Gestión y un perfil distinto de `integracion-local`, por ejemplo `SPRING_PROFILES_ACTIVE=default`. El perfil existente sin base de datos no publica estos endpoints.

| Variables | Uso |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Persistencia de Gestión y migraciones Flyway. |
| `SEGURIDAD_URL` | Origen de Seguridad; descubrimiento y JWKS para tokens entrantes y emisión de credenciales salientes. |
| `PRODUCTOS_URL` | Origen del proveedor de datos físicos. |
| `PRODUCTOS_CLIENT_ID`, `PRODUCTOS_CLIENT_SECRET` | Emisión de token propio con `productos:fisicos:leer`; alternativamente, token vigente de servidor en `PRODUCTOS_TOKEN_SERVICIO`. |
| `OPERACION_REPARTO_URL` | Origen privado de Operación. |
| `OPERACION_CLIENT_ID`, `OPERACION_CLIENT_SECRET`, `OPERACION_CAPACIDAD_SCOPES` | Credenciales/permisos internos configurados conforme al proveedor; alternativamente, `OPERACION_TOKEN_SERVICIO`. No se presupone una concesión de scopes que no esté publicada. |
| `VENTAS_URL` | Origen del webhook de Ventas. |
| `VENTAS_CLIENT_ID`, `VENTAS_CLIENT_SECRET` | Emisión con `ventas:pedidos:estado`; alternativamente, `VENTAS_TOKEN_SERVICIO`. |

Los tokens salientes se solicitan mediante `client_credentials`, se renuevan y no reutilizan el JWT humano ni el token recibido de Ventas. Los orígenes y credenciales reales deben configurarse en el servidor. La plantilla está en `servicio-gestion-despachos/.env.example`.

Para ejecutar desde ese directorio, con las variables configuradas:

```bash
bash mvnw spring-boot:run
```

La creación necesita cobertura activa y un plazo operativo vigente de F-01. El calendario implementado utiliza días hábiles de lunes a viernes y la fecha civil de Lima, como el plan. Si no hay plazo configurado, no inventa una fecha ni crea el despacho.

## 7. Verificación reproducible

Desde `servicio-gestion-despachos`, con Java 21 y Docker disponible:

```bash
bash mvnw verify
```

La alternativa ejecutada utiliza PostgreSQL real externo, en una base exclusiva de pruebas cuyas tablas vacían las fixtures:

```bash
bash mvnw -q -Dpruebas.postgres.externo=true \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55432/despacho_pruebas \
  -Dspring.datasource.username=pruebas \
  -Dspring.datasource.password= verify
```

Entorno real de esta ejecución: Linux, Temurin 21.0.12.1, PostgreSQL 18.6 local aislado, Spring Boot 4.1.1. No había daemon Docker disponible; no se omiten suites ni se sustituye PostgreSQL por H2. La prueba JDBC histórica de F-04 usa un esquema aislado al seleccionar PostgreSQL externo.

| Registro | Resultado observado |
|---|---|
| `ProgramacionHttpTests` | 23 pruebas aprobadas con MockMvc, descubrimiento/JWKS y JWT RS256 reales, PostgreSQL/Flyway/JPA y dobles HTTP de Productos, Operación y Ventas. |
| `AsignarDespachoTests` | 1 prueba aprobada de carrera entre confirmación y compensación; no libera una reserva ya confirmada por otra recuperación. |
| `ProveedorTokenServicioTests` | 2 pruebas aprobadas de scope propio, caché/renovación, caída de emisión y respuestas no aceptadas. |
| Regresión de Gestión | Total **69 pruebas, 0 fallos, 0 errores, 0 omitidas**, incluidas F-01, F-04 y validación de seguridad. |
| Compilación y empaquetado | `mvnw verify` correcto; JAR ejecutable generado. |
| Rendimiento local | Página de 100 sobre 10 000 pendientes: **29 ms** en la última ejecución, después de calentamiento; no representa carga concurrente de producción. |
| Migraciones | V1, V2 y V3 aplicadas inicialmente sobre base vacía y validadas en las ejecuciones posteriores. |
| Higiene | `git diff --check` sin errores. |

Los informes completos se generan en `servicio-gestion-despachos/target/surefire-reports/`. Los dobles HTTP verifican rutas, claves y credenciales propias; no acreditan pruebas contra entornos reales de G7, Productos, Ventas o F-06.

## 8. Trazabilidad

| Especificación y criterios backend | Implementación | Comprobaciones |
|---|---|---|
| ES-F02-01, CA-01 a CA-07 | `RecibirSolicitudDespacho`, `ProductosProgramacionHttp`, `CoberturaProgramacionLocal`, `ProgramacionPersistencia`, `ControladorProgramacion` | Creación, sumas y conversión cm³/m³, zona activa, validación estricta, idempotencia/concurrencia, rollback, errores de producto/timeout y autenticación. |
| ES-F02-02, CA-01 a CA-04 | `GenerarDespachoPrueba` y controlador/persistencia comunes | Valores por omisión/personalizados, Lima, precisión decimal, zona solo postal, rechazo de parámetros/zona y exclusión de eventos comerciales. |
| ES-F02-03, parte backend de CA-01 a CA-06 | `ConsultarColaPendientes`, `ParametrosPendientes`, consulta consistente e indexada | Estado estricto, fechas/zona/tipo, contador 0/1 con reprogramación, paginación/vacío, KPIs, fecha futura y acceso. Los badges y botones de la pantalla no se verifican aquí. |
| ES-F02-04, parte de Gestión de CA-01 a CA-07 | `AsignarDespacho`, `CapacidadProgramacionHttp`, intención durable, `@Version`, historial y outbox | Rechazos 422/409/401/403, fecha futura, reserva previa activa, claves nuevas/repetidas, respuesta perdida, compensación/recuperación, concurrencia y secuencia única. La validación efectiva de capacidad en el proveedor F-06 se simula en estas pruebas. |

La pantalla funcional de F-02 y los criterios CUI de las atómicas siguen en sus tareas de frontend; los componentes compartidos previamente entregados no acreditan esa integración.

## 9. Referencias

1. Especificaciones ES-F02-01 a ES-F02-04 y sus planes/tareas en Specs `91db8f8`.
2. [Integraciones y convenciones comunes](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/91db8f8/integraciones/api-contract.md), §§3, 5.2, 7, 8 y 9.
3. [API de Operación](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/91db8f8/integraciones/api-contract-operacion-reparto.md), §§5.1–5.3.
4. [API de Gestión](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/91db8f8/integraciones/api-contract-gestion-despachos.md), §3.2, y modelo de datos del mismo repositorio.
