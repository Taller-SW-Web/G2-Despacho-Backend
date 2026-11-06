# Plan de persistencia de cotizaciones exactas

## 1. Estado, objetivo y fuentes

**Estado:** plan técnico pendiente de implementación y verificación. Revisión del código: 02/11/2026.

El objetivo es que Gestión confirme en su base una instantánea de cada cotización exacta exitosa antes de devolverla a Marketplace o Chatbot. Esto ya está previsto en ES-F01-06; no depende de que el cliente compre [cite: 1, 2].

Este documento organiza el trabajo del backend y referencia las fuentes canónicas. No modifica contratos, no acredita implementación y no reemplaza los documentos SDD de Specs. La asociación posterior con Ventas queda aplazada por decisión del solicitante; su intención futura se registra en la sección 7.

## 2. Situación actual comprobada en el código

| Elemento | Situación |
|---|---|
| `cotizacion.application.ServicioCotizacion` | Resuelve cobertura, tarifa y productos; calcula y devuelve `ResultadoCotizacion`, sin registrar una cotización. |
| `cotizacion.api.RespuestaCotizacion` | Expone el resultado, sin identificador público de cotización. |
| `cotizacion.infrastructure.CatalogoProductosStub` | Proveedor prioritario para la cotización; utiliza datos de productos simulados. |
| `zonas.application.TarifaVista` | Contiene términos tarifarios, pero no el identificador de la fila de tarifa. |
| Tabla `public.cotizaciones` | Ya está declarada en V2; tiene columnas de resultado, tarifa, vínculos opcionales y `detalle` JSONB [cite: 3]. |
| `cotizaciones.pedido_id` | UUID opcional, mientras que `despachos.pedido_id` admite texto. |

La existencia de la tabla y sus campos no implica que estén implementados el escritor, la aceptación comercial, el vencimiento ni la vinculación con una compra.

## 3. Alcance del incremento

- Guardar una fila por ejecución exacta exitosa y conservar la tarifa y los datos físicos realmente utilizados.
- Construir la respuesta y la instantánea desde el mismo cálculo.
- Mantener `pedido_id`, `despacho_id` y `vence_en` nulos según el alcance vigente.
- Excluir de la escritura las consultas de solo cobertura, destinos sin cobertura, zonas sin tarifa y rechazos.
- Devolver el error contractual de persistencia cuando falle la inserción o su confirmación, sin una respuesta exitosa con costo.
- Conservar el esquema público actual; no añadir `idCotizacion` como parte de este incremento.
- Mantener F-02 independiente de este histórico: la creación del despacho continúa consultando los datos físicos vigentes y no exige cotización previa [cite: 2].

La selección del proveedor real de Productos se desarrolla en el [plan de integración](./plan-integracion-productos.md). Las pruebas con el catálogo simulado no acreditan datos reales de G6.

## 4. Diseño propuesto

### 4.1. Resultado interno e instantánea de tarifa

Introducir un resultado interno inmutable que reúna lo necesario para responder y persistir, evitando volver a consultar o recalcular después:

- Zona resuelta y destino recibido; referencia de distrito cuando corresponda al modelo.
- Identificador de la tarifa capturada, sus términos, moneda y plazo.
- SKU agrupados con las cantidades acumuladas, perfil físico utilizado y marca de actualización cuando el proveedor real esté conectado.
- Totales físicos, componentes del costo, costo final, fecha estimada e instante de cotización.

Obtener la identidad de la tarifa desde un modelo interno de aplicación, sin agregarla a `TarifaVista` pública solo para resolver la persistencia. Una sustitución concurrente o posterior de tarifa no debe hacer que la fila referencie una tarifa distinta de la usada en el cálculo.

### 4.2. Puerto y adaptador de persistencia

Agregar un puerto de aplicación para registrar la instantánea y un adaptador de infraestructura sobre la tabla existente. Los nombres concretos se ajustarán al patrón del servicio; una posible organización es `RepositorioCotizaciones` y `RepositorioCotizacionesJdbc`, aprovechando `JdbcClient` y la gestión transaccional ya utilizados en Gestión.

El adaptador debe insertar la instantánea completa en una sola transacción y usar el mismo instante de la respuesta. Las columnas de actor UUID no deben recibir un UUID inventado a partir de `sub=modulo-marketplace` o `modulo-chatbot`; la identidad técnica se conservará únicamente conforme al modelo canónico [cite: 4].

### 4.3. Correspondencia de datos y precisión

Antes de escribir el adaptador, contrastar la migración versionada con el modelo de Specs:

| Tema | Trabajo requerido |
|---|---|
| Totales y términos tarifarios | Documentar la correspondencia con las columnas existentes, incluyendo `tarifa_zona_id`, `total` y los componentes derivados. |
| Precisión | Conciliar las escalas de peso `numeric(10,2)` y volumen `numeric(10,3)` con el cálculo; definir redondeo explícito y evitar redondeos intermedios. |
| Instantánea detallada | Conservar en `detalle` el desglose y los datos fuente previstos por el modelo, sin reducirlos a los valores redondeados de columnas resumidas. |
| Plazos y fechas | Conciliar las columnas de plazo con el plazo único y la fecha estimada devueltos por el servicio. |
| Estado y simulación | Respetar el estado físico inicial y los valores definidos por el modelo; no habilitar transiciones por la mera existencia de columnas. Registrar los dobles utilizados en las pruebas. |
| Referencias opcionales | Mantener nulos los vínculos a pedido y despacho y los datos de ubicación no suministrados. |

Conservar la fórmula canónica de F-01. Los datos de prueba del catálogo actual no tienen las mismas dimensiones que todos los ejemplos del proveedor real; no alterar datos ni debilitar pruebas para hacer coincidir un resultado por casualidad.

### 4.4. Límite transaccional y errores

Flujo previsto:

```text
Autenticar y validar destino
        ↓
Resolver cobertura, líneas, tarifa y datos físicos
        ↓
Calcular y construir una instantánea completa
        ↓
Abrir transacción local → insertar → confirmar
        ↓
Responder con el mismo resultado exacto
```

La llamada HTTP a Productos queda fuera de la transacción de escritura. La respuesta `200` con `tipoCotizacion=EXACTA` solo se construye como éxito de la operación una vez confirmado el registro.

Capturar tanto fallos de inserción como de commit en el límite correcto. Aplicar `500 DESP_ERROR_PERSISTENCIA_COTIZACION`, según el contrato, sin traducirlo como indisponibilidad de Productos ni exponer SQL o detalles de infraestructura.

La cotización no requiere `Idempotency-Key`. Repetir una solicitud exacta exitosa puede producir otra fila y una tarifa diferente; la instantánea histórica no es una reserva de precio.

## 5. Incrementos y criterios de aceptación

| Incremento | Entrega | Criterio verificable |
|---|---|---|
| P-01 | Correspondencia de esquema, precisión y resultado interno. | Se puede construir una instantánea completa sin releer otra tarifa ni inventar referencias. |
| P-02 | Puerto y adaptador transaccional. | Una escritura exitosa confirma una fila íntegra; un fallo revierte la transacción. |
| P-03 | Integración con `ServicioCotizacion` y manejo de errores. | El costo se devuelve solo después de persistir; cobertura y rechazos no escriben. |
| P-04 | Regresión y comprobación manual en entorno aislado. | Fila, desglose y respuesta coinciden; las pruebas existentes conservan sus aserciones. |

Si el esquema requiere cambios, agregar la siguiente migración Flyway disponible. La revisión actual contiene V1 a V7, por lo que V8 sería la siguiente si no se incorporan otras migraciones antes. No reservarla ni modificar V2 como parte de este plan. La conciliación con bases persistentes compartidas se realiza por separado, sin activar migraciones para probar.

## 6. Verificación prevista

Usar PostgreSQL/PostGIS desechable con Testcontainers y dependencias controladas. Cubrir:

- Una fila por resultado exacto exitoso, con vínculos nulos y datos coherentes con la respuesta.
- Cambio de tarifa después de cotizar, conservando intacta la instantánea anterior.
- Repetición de la solicitud, conservando instantáneas independientes.
- Ninguna fila nueva en solo cobertura, falta de cobertura, falta de tarifa, validaciones, límites o errores del proveedor.
- Fallos durante inserción y commit: rollback y error de persistencia sin costo exitoso.
- Precisión decimal, cantidades agrupadas y coherencia de fecha, plazo y desglose.
- Preservación de seguridad y del comportamiento de F-02.

Al implementar, ejecutar dentro de `servicio-gestion-despachos`:

```bash
./mvnw clean verify
```

En Windows, usar `.\mvnw.cmd clean verify`. Registrar resultados reales y revisión en la evidencia SDD. La suite no debe apuntar a Supabase ni a la base local de trabajo. Después se puede repetir el recorrido de [Postman](./pruebas-postman-local.md) sobre una base de pruebas y contrastarlo con [pgAdmin](./conexion-pgadmin-local.md).

## 7. Ampliación futura aplazada: pedido al pagar y despacho después

**Decisión del solicitante:** dejar esta parte para otro momento. La preferencia expresada es estudiar la vinculación del pedido al confirmar el pago. No se implementa ni se declara acordada con Ventas en este incremento.

El recorrido a evaluar sería:

```text
Cotización exacta → guardar histórico sin vínculos
Pago confirmado por Ventas → asociar pedido_id
Pedido preparado y listo → crear despacho y asociar despacho_id
```

La recomendación es que la confirmación de pago provenga del backend de Ventas. El pago no garantiza que ya exista un despacho: el contrato vigente lo crea cuando el pedido está pagado, preparado y listo para entrega. Por eso ambos IDs no se deben dar por disponibles inmediatamente al pagar.

Antes de desarrollar esa ampliación hay que acordar en Specs y con Ventas:

1. La referencia que identifica la cotización seleccionada entre varias solicitudes del cliente y cómo la conserva el canal y Ventas. La API actual no expone un `idCotizacion`.
2. La comunicación de pago, autenticación y permisos, contenido y confirmación esperada. Este plan no inventa una nueva ruta ni un scope.
3. La compatibilidad de `cotizaciones.pedido_id` UUID con el identificador opaco textual de Ventas, incluida una migración nueva si corresponde.
4. Las reglas de pertenencia y coincidencia de destino y líneas, sin asociar por fecha aproximada, importe o último registro disponible.
5. La idempotencia, concurrencia y cardinalidad: reconocer repeticiones, impedir reasignaciones indebidas y conservar la asociación original.
6. La coordinación recuperable entre pago, vinculación y creación de despacho; no compartir transacciones ni tablas con Ventas.
7. El tratamiento de cambios de carrito, anulaciones y diferencias entre costo logístico histórico e importe comercial cobrado, sin reescribir el cálculo original.

Hasta ese acuerdo, F-02 conserva su contrato actual y los vínculos del histórico permanecen nulos. Los cambios de contrato se documentan en Specs, no en un contrato paralelo en este backend.

## 8. Referencias

- [cite: 1] [ES-F01-06: especificación](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-06-CotizarEnvioConProductos/spec.md) y [plan SDD](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-06-CotizarEnvioConProductos/plan.md), en particular CA-11 a CA-13.
- [cite: 2] [Contrato externo](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md), §§5.1 y 7.1. [ES-F02-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F02-01-RecepcionSolicitudDespacho/spec.md).
- [cite: 3] [V2: esquema existente de cotizaciones](../src/main/resources/db/migration/V2__actualizacion_esquema_gestion.sql).
- [cite: 4] [Modelo de datos canónico](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/arquitectura/modelo-datos.md), §3.1.
