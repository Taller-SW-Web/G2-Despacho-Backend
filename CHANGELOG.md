# Historial de cambios

Los cambios de cada entrega se consolidan al preparar una línea base en `main`, según la [guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md). Los tags se crean sobre los commits de entrega aprobados.

## 0.2.0 — 2026-11-09

La fecha identifica la preparación del corte.

### Añadido

- F-02: consulta y reordenamiento de rutas, reasignación con motivo y transferencia de reservas, y cancelación por anulación del pedido con recuperación de solicitudes.
- F-03: comandos idempotentes de inicio de traslado, entrega y fallo con evidencia en Operación. Gestión coordina las transiciones, el historial, la proyección, la liberación de capacidad y los eventos a Ventas. Las consultas del repartidor incluyen hitos y el resumen de jornada.
- F-04: confirmación de recepción en centro con liberación recuperable de capacidad, reprogramación y cierre administrativo como `DEVUELTO_A_ORIGEN`. Gestión conserva el resultado de los comandos y registra su historial.
- F-01: consulta de los datos físicos de Productos (G6), persistencia de cotizaciones exactas con su instantánea de tarifa y redondeos acordados. El catálogo simulado se activa mediante un perfil de prueba.
- RT-04: consulta de seguimiento por pedido para los canales autorizados.
- Gateway: rutas de acceso humano, MFA, renovación y cierre de sesión. Seguridad temporal incorpora cuentas ficticias para probar los dos perfiles.
- CI/CD: pruebas y construcción de imágenes, publicación en GHCR desde `main`, compuerta de despliegue en Render y comprobación posterior de salud.
- Diagnóstico opcional de salud de Seguridad (G7) y Productos (G6) para las pruebas de cotización.

### Cambiado

- Gestión y Operación autentican a `modulo-despacho` en rutas internas sin scopes internos. Los permisos de las rutas externas se mantienen.
- El cierre de jornada del repartidor resuelve los pendientes como `FALLIDO` con motivo `NO_INTENTADO` antes de cerrar y conserva la clave de idempotencia.
- El entorno local usa un Compose con Gateway, Gestión, Operación, Seguridad temporal y PostgreSQL/PostGIS. Las bases separadas aplican sus propias migraciones.
- F-05/F-06: validación JWT alineada con el contrato de Seguridad y auditoría de cierres de jornada por baja de repartidor o retiro de vehículo.

### Corregido

- Compatibilidad con las respuestas de firma de carga y lectura de evidencias, y encabezados CORS duplicados en el acceso.
- Conteo de despachos no intentados en el resumen del repartidor y correspondencia de distrito y código postal en simulaciones.

## 0.1.0

Primera línea base de código documentada, correspondiente al commit del 17/10/2026 identificado por el tag [v0.1.0](https://github.com/Taller-SW-Web/G2-Despacho-Backend/tree/v0.1.0).

### Añadido

- Base del API Gateway y de los servicios Gestión de Despachos y Operación de Reparto, con Dockerfiles y configuración de despliegue.
- Implementaciones iniciales de zonas y cotización, programación y asignación, consultas de entregas fallidas y detalle, flota, jornadas y evidencias.
- Validación JWT, permisos y emisor temporal para pruebas locales.
- Pruebas unitarias, HTTP y de persistencia con PostgreSQL para los comportamientos implementados.
