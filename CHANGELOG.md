# Historial de cambios

Este archivo resume los cambios significativos del backend. Los cambios en integración se registran en `Sin publicar`; al preparar una entrega se agrupan en una versión con fecha y se promueven de `develop` a `main` mediante PR.

El equipo revisa el corte de entrega aproximadamente cada dos semanas. El número de versión cambia cuando hay una entrega de cambios, no automáticamente por el paso del tiempo. Durante el desarrollo inicial se utiliza `0.x.y`: nuevas capacidades en `0.2.0`, `0.3.0`, etc., y correcciones sobre una entrega en `0.1.1`, por ejemplo. Los tags se agregan manualmente; este proceso no crea GitHub Releases.

## Sin publicar

### Añadido

- CI para PR y cambios en `develop` y `main`: compilación y pruebas existentes de Gateway, Gestión y Operación, incluidas las integraciones con bases temporales; pruebas propias de Seguridad temporal.
- Instrucciones del backend enlazadas con las especificaciones y contratos del equipo.
- F-03, comandos del repartidor en Operación: inicio de traslado, entrega y fallo con evidencia verificada en el bucket, y catálogo de motivos de campo. Cada comando es idempotente por `Idempotency-Key` del usuario (migración V6 `comandos_repartidor`) y se coordina con Gestión fuera de las transacciones locales.
- F-03 en Gestión: `POST /internal/v1/despachos/{id}/transiciones` (contrato de Gestión §6.1) con máquina de estados, contador de intentos, incidencia vigente, historial F-03, eventos para Ventas, proyección para Operación y solicitud durable de liberación de capacidad al entregar. Catálogo interno de motivos para Operación (§6.2).
- Proyección y consultas del repartidor con `motivoFallo` y `hitos` (asignado, en camino, entregado o fallido); resumen ampliado con estado y hora de cierre y paquetes por devolver.
- Seguridad temporal: login propuesto `POST /api/v1/auth/login` con cuentas ficticias, enrutado por el Gateway sin JWT; scopes `gestion:despachos:transicionar` y `gestion:catalogos:leer` para `modulo-despacho`.

### Cambiado

- Entorno local unificado en `compose.yaml`, con los tres backends, Seguridad temporal y dos bases PostgreSQL locales.
- Historia local de Gestión reconstruida con V1 original y V2 acumulada; configuración de pruebas adaptada a PostGIS.
- Recursos de compatibilidad y datos utilizados por pruebas de Operación reubicados dentro del servicio.
- Documentación de ejecución y configuración consolidada en el README; eliminación de Compose y guías redundantes.

- El cierre de jornada del repartidor exige `Idempotency-Key` y resuelve los pendientes como `FALLIDO`/`NO_INTENTADO` en Gestión antes de cerrar, en lugar de rechazar el cierre.

### Corregido

- Resumen del repartidor: `noIntentados` contaba un estado `NO_INTENTADO` que Gestión no emite; ahora usa el motivo del `FALLIDO`.

- Preparación de las pruebas F01: conservar el catálogo de distritos creado por Flyway al limpiar los escenarios.
- Generación de despachos simulados: consultar también `zonas_codigos_postales` y conservar la correspondencia entre distrito y código postal; adaptar la prueba de cobertura postal a las restricciones del esquema vigente.

## 0.1.0

Primera línea base de código documentada. Se toma como referencia el commit [0ab1609](https://github.com/Taller-SW-Web/G2-Despacho-Backend/tree/0ab16093007401cbfbe61027ea31b2cf0e7ad2c2), actualmente en `main`, del 17/10/2026. Esa fecha identifica el commit de referencia, no una publicación ni un tag ya creado. Los cambios posteriores de `develop` figuran arriba en `Sin publicar`.

### Añadido

- Base del API Gateway y de los servicios Gestión de Despachos y Operación de Reparto, con Dockerfiles y configuración de despliegue.
- Implementaciones iniciales de zonas y cotización, programación y asignación, consultas de entregas fallidas y detalle, flota, jornadas y evidencias.
- Validación JWT, permisos y emisor temporal para pruebas locales.
- Pruebas unitarias, HTTP y de persistencia con PostgreSQL para los comportamientos implementados.
