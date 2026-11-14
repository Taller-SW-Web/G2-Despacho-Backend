# Plan de F-01: administración de distritos

**Fecha:** 12/11/2026.

**Estado:** Implementado y verificado localmente el 13/11/2026; revisión del equipo y aceptación humana pendientes.

**Servicio propietario:** Gestión de Despachos.

**Ámbito:** Lima Metropolitana y Callao.

Este documento conserva el plan de trabajo acordado para añadir la administración de distritos y su interfaz. Los requisitos, las decisiones contractuales y la evidencia se formalizarán en [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs). Las rutas y los datos propuestos aquí son entradas para esa actualización; el contrato canónico seguirá siendo el [contrato de Gestión](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md), junto con las [convenciones compartidas](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md). [cite: 1, 2]

La decisión definitiva es que **un distrito inactivo queda sin cobertura para nuevas cotizaciones y nuevos despachos**. Reemplaza la propuesta inicial de conservar su cobertura durante la baja. Sus asociaciones y los despachos existentes sí se conservan.

## 1. Situación actual y objetivo

El catálogo inicial se precarga mediante Flyway: 43 distritos de Lima y siete del Callao en `V2__actualizacion_esquema_gestion.sql`. Actualmente existe `GET /api/v1/distritos`, con filtro opcional por nombre, y el frontend lo utiliza para configurar zonas. Todavía no hay administración de distritos mediante APIs de escritura ni una pantalla propia. [cite: 3]

El objetivo es permitir crear, consultar, editar, desactivar y reactivar distritos desde la aplicación administrativa, manteniendo compatible la integración de cotización que consume Chatbot.

## 2. Comportamiento esperado

Ejemplo: San Borja pertenece a una zona activa con tarifa vigente.

| Situación | Cotización desde Chatbot | Solicitud de un despacho nuevo |
|---|---|---|
| Distrito activo | Cotiza normalmente. | Se procesa con las validaciones actuales. |
| Distrito inactivo | Responde sin cobertura, sin costo ni plazo. | Se rechaza por destino sin cobertura. |
| Distrito reactivado | Vuelve a cotizar con la configuración vigente. | Vuelve a admitir solicitudes. |
| Distrito activo, pero zona inactiva | Sin cobertura. | Se rechaza por destino sin cobertura. |
| Distrito nuevo sin zona asignada | Sin cobertura. | Se rechaza por destino sin cobertura. |

Reglas adicionales:

- Desactivar San Borja no desactiva los demás distritos de su zona.
- Se conservan sus asociaciones, códigos postales y tarifas.
- Los despachos ya registrados continúan su ciclo, incluidas sus reprogramaciones.
- Las cotizaciones históricas conservan sus datos.
- Una cotización previa no reserva cobertura: si el despacho todavía no existe y su solicitud llega después de la baja, se rechaza.
- Reactivar el distrito no activa automáticamente una zona ni crea una tarifa.

## 3. Compatibilidad con Chatbot

La integración seguirá utilizando:

- `POST /api/v1/cotizaciones`.
- El cuerpo actual con `destino` obligatorio y `lineas` opcionales.
- La autenticación técnica y el permiso `cotizaciones:calcular`.
- Los formatos actuales de respuesta y errores.
- Las reglas actuales de cálculo, redondeo, moneda y plazo.

Para un distrito inactivo se reutilizará la respuesta habitual de destino sin cobertura, definida en el contrato externo, con `200 OK`, sin costo ni plazo. No se consultará Productos ni se guardará una cotización exacta para ese resultado. La única ampliación de comportamiento será considerar el estado del distrito al resolver la cobertura. [cite: 2]

### 3.1. Protección frente a ediciones accidentales

Actualmente la cobertura se busca por el nombre del distrito. Conservar únicamente su UUID no protege la integración si Chatbot sigue enviando un nombre que fue modificado.

El plan incorpora estas restricciones:

- Proteger nombre y ubigeo de los 50 distritos precargados.
- Permitir modificar esos campos en distritos nuevos mientras no tengan asociaciones de cobertura.
- Mantener nombres y ubigeos únicos, incluidos los registros inactivos.
- Aplicar estas restricciones en el backend y reflejarlas en el formulario.

Así se conserva el vocabulario que Chatbot ya envía. Los demás datos podrán editarse respetando el ámbito geográfico y sus validaciones.

## 4. APIs administrativas propuestas

Gestión de Despachos será el propietario. Todas las operaciones se consumirán por el Gateway con JWT humano y rol `GESTOR_DESPACHO`.

La siguiente tabla es la propuesta que se formalizará una sola vez en el contrato de Gestión antes de implementar sus consumidores. [cite: 1]

| Método | Ruta propuesta | Resultado esperado |
|---|---|---|
| `GET` | `/api/v1/distritos` | Listado con búsqueda por nombre y filtro de estado. |
| `GET` | `/api/v1/distritos/{id}` | Detalle de un distrito. |
| `POST` | `/api/v1/distritos` | Crear un distrito activo. |
| `PUT` | `/api/v1/distritos/{id}` | Editar sus datos permitidos. |
| `DELETE` | `/api/v1/distritos/{id}` | Dar de baja lógicamente. |
| `PATCH` | `/api/v1/distritos/{id}/estado` | Activar o desactivar. |

`DELETE` y el cambio a `INACTIVO` utilizarán el mismo caso de uso.

### 4.1. Datos y validaciones

Datos principales previstos:

- `id`.
- `nombre`.
- `provincia`.
- `departamento`.
- `ubigeo`.
- `estado`: `ACTIVO` o `INACTIVO`.
- `versionDistrito`.
- Fechas de creación y actualización.

Validaciones:

- Nombre obligatorio, normalizado en espacios extremos.
- Ubigeo como texto de seis dígitos, conservando ceros iniciales.
- Combinación válida de provincia, departamento y prefijo de ubigeo para Lima Metropolitana y Callao.
- Unicidad de ubigeo y de nombre normalizado dentro del catálogo administrado, sin distinguir mayúsculas en el nombre.
- Estado modificable exclusivamente mediante las operaciones correspondientes.
- Control de versión para rechazar modificaciones realizadas desde una vista desactualizada.

La consulta existente conservará su respuesta como arreglo para mantener compatible el selector de zonas. Por defecto devolverá activos; administración podrá solicitar inactivos o todos.

Se utilizará Problem Details para validaciones y conflictos, conforme a las convenciones compartidas. Repetir una baja o activación que ya coincide no generará otra auditoría. Los cuerpos, las precondiciones de versión y las respuestas de las nuevas operaciones se concretarán en el contrato de Gestión. [cite: 1, 2]

## 5. Backend y base de datos

### 5.1. Persistencia

Agregar una migración con la siguiente versión disponible, V11 según el checkout revisado, para incorporar:

- Estado del distrito.
- Control de versión.
- Identificación de los registros del catálogo base protegidos.
- Restricciones e índices necesarios.

Los registros existentes comenzarán activos, conservando sus UUID, nombres, ubigeos y relaciones. La migración será nueva; no se modificarán las migraciones ya aplicadas.

### 5.2. Casos de uso y auditoría

Extender los componentes actuales de distritos dentro de `zonas/`:

- `ControladorDistritos`.
- `GestionDistritos`.
- `RepositorioDistritos`.
- Adaptadores y repositorios JPA.

Cada creación, edición o cambio efectivo de estado registrará valores anteriores y nuevos, usuario autenticado y fecha UTC. El cambio y su auditoría se confirmarán en la misma transacción.

La identidad del ejecutor procederá del JWT y la fecha, del servidor. Una operación rechazada o una repetición sin cambio efectivo no dejará auditoría de una nueva decisión aceptada. La ampliación se coordinará con ES-F01-08. [cite: 4]

### 5.3. Resolución de cobertura

Este es el ajuste central para cumplir el comportamiento confirmado:

1. Si el distrito recibido existe y está inactivo, devolver sin cobertura.
2. Si está activo, aplicar la resolución actual, priorizando cobertura distrital y después postal.
3. Las coberturas postales deberán pertenecer a distritos activos.
4. Un código postal, incluso de otra cobertura activa, no podrá eludir la baja del distrito informado.
5. Mantener una resolución compartida para cotización, consulta de cobertura y recepción de despachos.

Los puntos de implementación identificados son `GestionZonas`, `ZonasAdaptadorJpa`, `ZonaDistritoRepositorioJpa` y `ZonaCodigoPostalRepositorioJpa`. `ServicioCotizacion` y `CoberturaProgramacionLocal` deberán obtener resultados coherentes de esa resolución.

También se ajustará la generación de despachos simulados: deberá seleccionar una cobertura de un distrito activo.

### 5.4. Integración con zonas

- Las asociaciones nuevas exigirán distritos activos.
- Una zona podrá conservar o retirar sus asociaciones con distritos inactivos.
- El detalle mostrará cuáles de sus distritos están inactivos.
- La baja conservará las reglas actuales de exclusividad entre zonas; no liberará automáticamente el distrito para asignarlo a otra zona activa.
- Reactivar recuperará la disponibilidad de la cobertura conservada, siempre que la zona esté activa.

### 5.5. Recepción y ciclo de despachos

- Una solicitud nueva hacia un distrito inactivo se rechazará con el tratamiento vigente de destino sin cobertura.
- La baja no modificará estados, zonas, destinos, reservas ni historial de despachos existentes.
- La recuperación idempotente de una creación ya aceptada conservará su resultado original, antes de evaluar de nuevo la cobertura.
- La reprogramación y las demás operaciones sobre despachos existentes conservarán sus reglas actuales.

## 6. Frontend administrativo

Añadir la sección Distritos dentro de F-01 en [G2-Despacho-Frontend](https://github.com/Taller-SW-Web/G2-Despacho-Frontend).

### 6.1. Listado

- Nombre, provincia, departamento, ubigeo y estado.
- Búsqueda por nombre.
- Filtro Activos / Inactivos / Todos.
- Acciones Crear, Editar, Desactivar y Reactivar.
- Estados de carga, error, vacío y acceso denegado.

### 6.2. Formulario

- Reutilizar Mantine, React Hook Form, Zod y los componentes compartidos existentes.
- Mostrar las restricciones de edición del catálogo base.
- Presentar errores por campo y conservar valores ante fallos.
- Actualizar el catálogo después de cada operación.
- Mantener las convenciones existentes de accesibilidad, teclado, foco y adaptación móvil.

### 6.3. Confirmación de desactivación

El mensaje explicará el efecto real. Ejemplo:

> San Borja dejará de admitir nuevas cotizaciones y solicitudes de despacho. Los despachos existentes continuarán su proceso. Sus asociaciones con zonas se conservarán.

La reactivación requerirá una acción explícita del Gestor y actualizará el estado mostrado después de confirmar el resultado.

### 6.4. Adaptación del formulario de zonas

- Mostrar los distritos asociados inactivos, sin perderlos al editar.
- Señalar que no ofrecen cobertura mientras permanezcan en ese estado.
- Ofrecer únicamente distritos activos para asociaciones nuevas.
- Conservar las selecciones válidas y los cambios del formulario ante errores.

Los puntos de extensión existentes incluyen `Aplicacion.tsx`, `useDistritos`, `zonasApi.ts`, `tipos.ts` y `FormularioZona.tsx` en la aplicación administrativa. La ubicación definitiva de la nueva funcionalidad respetará la estructura del frontend.

## 7. Especificaciones y documentación

En G2-Despacho-Specs:

- Ampliar F-01 con administración de distritos.
- Crear especificaciones atómicas para consultar distritos, registrar un distrito, editar un distrito y activar o desactivar un distrito.
- Incluir `spec.md`, `plan.md`, `tasks.md`, `ui.md` y `evidence.md` en las nuevas carpetas SDD.
- Actualizar el contrato canónico de Gestión y el modelo de datos.
- Ajustar las especificaciones relacionadas con cobertura, cotización, zonas, trazabilidad y recepción de despachos.
- Actualizar el índice funcional y su trazabilidad.

La evidencia distinguirá pruebas automatizadas, APIs simuladas, integración por Gateway y aceptación en navegador. Este plan local no reemplaza esos documentos ni acredita implementación. [cite: 1, 4, 5]

## 8. Pruebas y criterios de aceptación

### 8.1. CRUD y seguridad

- Creación y edición válidas.
- Duplicados y datos geográficos inválidos.
- Protección de identidad del catálogo base.
- Restricción de cambios de identidad en distritos nuevos con asociaciones.
- Baja y reactivación conservando el identificador.
- Cambios concurrentes y formularios desactualizados.
- Auditoría transaccional y ausencia de duplicados en cambios repetidos.
- Acceso sin token, con otro rol o con token técnico.

### 8.2. Cotización y cobertura

Recorrido obligatorio:

1. Cotizar San Borja activo y comprobar el resultado actual.
2. Desactivarlo por API.
3. Repetir la cotización y comprobar sin cobertura.
4. Repetir con código postal y comprobar que sigue sin cobertura.
5. Probar un código postal de otra cobertura activa y comprobar que no elude la baja del distrito informado.
6. Verificar que otro distrito activo de la misma zona sigue cotizando.
7. Reactivar San Borja y comprobar que recupera su cotización.

Con tarifa, productos y reloj controlados, se compararán costo, moneda, peso, volumen y plazo antes de la baja y después de la reactivación.

También se comprobará:

- Cobertura exclusivamente postal de distritos activos e inactivos.
- Distrito activo sin zona y distrito activo con zona inactiva.
- Zona activa sin tarifa, conservando el comportamiento actual.
- Ausencia de consultas a Productos y de nuevas cotizaciones exactas al responder sin cobertura.
- Conservación de las cotizaciones históricas.

### 8.3. Despachos

- Una solicitud nueva hacia un distrito inactivo devuelve `422 DESP_ERROR_SIN_COBERTURA`.
- Una cotización previa no habilita una creación posterior a la baja.
- Un despacho existente continúa su ciclo, incluidas sus reprogramaciones.
- Recuperar una creación ya aceptada mediante su mecanismo idempotente conserva el resultado original.
- Los simuladores seleccionan únicamente distritos activos.

### 8.4. Frontend e integración

- Crear, consultar, editar, desactivar y reactivar desde la interfaz.
- Presentar los campos protegidos y los conflictos recibidos del backend.
- Confirmar el efecto de la baja antes de enviarla.
- Actualizar filtros y catálogo después de las mutaciones.
- Conservar distritos asociados inactivos al editar una zona.
- Verificar el recorrido en navegador por el Gateway.
- Confirmar que las solicitudes y respuestas existentes de Chatbot mantienen su contrato.

### 8.5. Comandos de verificación

Desde `servicio-gestion-despachos/`:

```bash
./mvnw clean verify
```

Las integraciones utilizarán Docker y bases desechables de Testcontainers.

Desde la raíz de G2-Despacho-Frontend:

```bash
npm run test:f01
npm run test:administrativo
npm run typecheck
npm run build
```

Si la nueva carpeta de distritos queda fuera del alcance actual de `test:f01`, se ajustará ese script para incluirla. Se ejecutarán las pruebas de componentes compartidos si se modifican esos componentes.

El recorrido integrado se hará en un entorno de prueba aislado. Las pruebas E2E actuales de F-01 escriben datos reales y no aíslan automáticamente su base; se verificará el entorno antes de ejecutarlas.

## 9. Orden de ejecución y entregables

| Orden | Incremento | Entregable verificable |
|---|---|---|
| 1 | Especificaciones y contrato | Comportamientos, UI, datos y operaciones formalizados en Specs. |
| 2 | Migración y CRUD backend | Persistencia, validaciones, autorización, auditoría y pruebas de administración. |
| 3 | Cobertura y regresiones | Baja y reactivación coherentes en cotización, consulta de cobertura y despachos. |
| 4 | Frontend | Administración de distritos y adaptación del formulario de zonas. |
| 5 | Verificación completa | Suites, recorrido por Gateway, revisión en navegador y evidencia con resultados reales. |

Las ramas de código partirán de `develop`, por ejemplo `feature/f01-administracion-distritos`. La rama documental partirá de `main` en Specs. La integración seguirá el flujo de PR de cada repositorio. [cite: 5]

El resultado esperado es administrar distritos desde el frontend y suspender o recuperar su cobertura, manteniendo compatible el contrato que consume Chatbot.

## 10. Estado de verificación

La preparación original del 12/11/2026 fue documental. El 13/11/2026 se ejecutó la ampliación en ramas `feature/f01-administracion-distritos` desde `develop` en backend y frontend, y `docs/f01-administracion-distritos` desde `main` en Specs.

- Especificaciones ES-F01-09 a ES-F01-12 y contrato canónico de Gestión actualizados en Specs; formatos definitivos y precondiciones se consultan allí.
- V11 implementada y probada tanto desde cero como sobre V10 con datos y asociaciones previas, preservando también las fechas originales.
- Gestión: `SPRING_FLYWAY_ENABLED=true ./mvnw clean verify`, 267 pruebas aprobadas, sin fallos ni omisiones; PostgreSQL/PostGIS desechable con Testcontainers. La variable habilita Flyway en el proceso de pruebas frente a la configuración local.
- Gateway: `./mvnw clean verify`, 34 pruebas aprobadas.
- Frontend: `npm run test:f01` (24 pruebas), `npm run test:administrativo` (234 pruebas), `npm run typecheck` y `npm run build` aprobados.
- Chromium: `npm run test:distritos:e2e`, dos recorridos aprobados con frontend, Gateway y Gestión reales locales, JWT de Seguridad temporal y Productos simulados; escritorio y móvil verificados. Entorno reproducible en la [guía de pruebas](../../docs/pruebas.md#administración-de-distritos-navegador-y-gateway-aislados).

La evidencia por criterio reside en las carpetas ES-F01-09 a ES-F01-12 de Specs. Revisión por pares, integración de ramas y aceptación humana siguen pendientes; estos resultados no acreditan G6/G7 publicados.

## 11. Referencias

1. [cite: 1] [Contrato de Gestión de Despachos](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md).
2. [cite: 2] [Contrato externo y convenciones compartidas](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md), especialmente cotización, seguridad, errores e idempotencia.
3. [cite: 3] [Migración V2: catálogo inicial](../src/main/resources/db/migration/V2__actualizacion_esquema_gestion.sql), [controlador de distritos](../src/main/java/com/modulodespacho/gestiondespachos/servicio_gestion_despachos/zonas/api/ControladorDistritos.java) y [regresiones actuales de F-01](../src/test/java/com/modulodespacho/gestiondespachos/servicio_gestion_despachos/FlujoF01Tests.java).
4. [cite: 4] [Funcionalidad F-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/funcionalidades/F-01-Gestor_ZonasGeograficas.md), [ES-F01-02: registrar y editar zonas](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-02-RegistrarZonaCobertura/spec.md) y [ES-F01-08: trazabilidad de configuración](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-08-RegistrarTrazabilidadConfiguracion/spec.md).
5. [cite: 5] [Reglas del backend](../../AGENTS.md), [reglas de Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/AGENTS.md), [guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md) e [índice funcional](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/funcionalidades/funcionalidad.md).
