# Jornadas, disponibilidad y capacidad F-06

**Responsable funcional original:** Luis Alfonso. **Implementación asumida:** Kevin Rhamses Bohorquez por solicitud del usuario.

## 1. Alcance

Operación conserva jornadas y reservas en Operadores y Flota. Gestión conserva sus despachos en Servicio Logístico. La apertura valida primero la zona mediante la API F-01 de Gestión y después crea jornada, fotografía de capacidades y auditoría en una transacción local. La validación remota usa el JWT humano recibido; no introduce credenciales compartidas en el código.

El contrato canónico de rutas, DTO, estados y permisos está en [Operación §4.2 y §5](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-operacion-reparto.md). Las [ocho especificaciones F-06](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/funcionalidades/funcionalidad.md) descomponen la implementación [cite: 1].

## 2. Consistencia e integración

- La fecha de apertura corresponde al día en `America/Bogota`. Una jornada activa o carga pendiente impide reutilizar repartidor o furgoneta, incluso si pertenece a otro día.
- Ocupación y remanente usan reservas `ACTIVA`, con aritmética decimal. El estado operativo prioriza fuera de turno, saturación y operación en ruta, en ese orden.
- Las escrituras comparten el bloqueo de flota con F-05. Reserva, cierre y proyección no pueden confirmar estados incompatibles; las restricciones únicas protegen recursos y despacho.
- La reserva guarda su primera confirmación por clave UUID v4. Repetirla después de liberar devuelve esa confirmación; reutilizar la clave para otro contenido produce conflicto.
- F-02 consume reserva y compensación; F-04 solicita liberación después de confirmar el retorno. Una proyección terminal o un `202` no libera carga por sí mismo.
- Una reserva activa sin proyección terminal confirmada impide cerrar. Los retornos fallidos conservan su carga al cerrar. El cierre propio exige el identificador de la jornada y verifica el perfil del JWT: repetir un cierre anterior no puede cerrar una jornada nueva.
- Las proyecciones de versión anterior o igual no sobrescriben datos nuevos. La proyección no crea ni libera reservas.

## 3. Configuración

En Operación configurar `GESTION_DESPACHOS_URL=https://taller-servicio-gestion-despachos.onrender.com`. Gestión consume `https://taller-servicio-operacion-reparto.onrender.com` mediante su configuración existente. El Gateway público es `https://taller-api-gateway.onrender.com`.

Las plantillas [Operación](../operaciones/render/operacion.env.example) y [Gestión](../operaciones/render/gestion.env.example) enumeran variables sin secretos. Gestión solicita los scopes técnicos de capacidad y proyección configurados; Seguridad debe concederlos realmente al sujeto `modulo-despacho`, audiencia `api-despacho`. Configurar una cadena de scopes no concede permisos.

El Gateway enruta la API pública y bloquea `/internal`. Los endpoints internos exigen JWT técnico y el scope específico de cada operación; un JWT humano o un permiso de otra operación es insuficiente. No desactivar JWT por la ausencia temporal de Seguridad.

## 4. Esquema nuevo y heredado

Instalaciones nuevas con Flyway aplican [V4](../servicio-operacion-reparto/src/main/resources/db/migration/V4__jornadas_capacidad.sql). Render conserva `SPRING_FLYWAY_ENABLED=false` según el despliegue existente.

Para el esquema heredado que ya recibió F-05 se incluye [la adaptación F-06](../operaciones/sql/F06__jornadas_capacidad_heredada.sql). Conserva la tabla singular y los nombres y orden de columnas de la vista plural existente. Mantiene el UUID histórico `pedido_id` y lo vuelve opcional; las nuevas proyecciones usan `id_pedido`, que admite códigos externos no UUID. No inventa claves de reservas históricas ni elimina duplicados. Si una restricción encuentra datos incompatibles, revierte. Es una migración de una sola ejecución, ya aplicada a Operadores y Flota tras ensayo aislado y ensayo con `ROLLBACK`; no repetirla en ese entorno.

`JornadasHeredadasTests` crea una copia sintética del esquema, incluida una vista plural preexistente, aplica las adaptaciones F-05/F-06 y verifica el recorrido de capacidad. Las suites usan PostgreSQL temporal y nunca deben apuntar a Supabase. La aplicación efectiva en Supabase se identifica en la evidencia de Specs.

## 5. Verificación reproducible y límites

Con Docker iniciado, ejecutar `mvn verify` dentro de `servicio-operacion-reparto`; Testcontainers prepara y retira PostgreSQL automáticamente. No hace falta instalar el proyecto dentro de Docker. La suite incluye apertura y auditoría, concurrencia de reservas/cierre, idempotencia, propiedad del perfil, versiones, compatibilidad heredada y permisos HTTP.

`JornadasPostgresTests` exporta `target/f06-respuestas-backend.json` para contrastar el consumidor frontend y el cliente F-02. También mide disponibilidad local con 1.001 perfiles, cinco calentamientos y veinte muestras; esa medición no acredita latencia en Render.

Ejecutar `mvn verify` en `api-gateway` valida el enrutamiento F-06. En Gestión, `ContratoCapacidadF06Tests` usa respuestas capturadas del proveedor PostgreSQL real para comprobar reserva y liberación con el adaptador F-02 existente.

Los JWT y la consulta de zona son dobles en las pruebas de persistencia. La aceptación desplegada requiere Seguridad publicada, permisos reales, cuentas vinculadas y la sesión frontend integrada. El módulo no implementa el login transversal ni completa las pantallas de F-02/F-03 de otros responsables.

La suite completa de Gestión encontró un problema preexistente: `V3__programacion_despachos.sql` y `V3__zonas_codigos_postales.sql` comparten versión Flyway. No se renumeran migraciones históricas sin conciliar los entornos que las hayan aplicado. El contrato F02/F06, asignación y proveedor de token se verificaron por separado con cuatro pruebas aprobadas. Este fallo se conserva en la evidencia y no se presenta como regresión aprobada. Render usa Flyway desactivado.

## 6. Referencias

1. Contrato y especificaciones canónicas enlazados en §1 [cite: 1].
2. [Configuración de despliegue de flota](./gestion-flota.md) [cite: 2].
