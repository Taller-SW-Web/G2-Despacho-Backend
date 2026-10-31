# Instrucciones para trabajar en el backend

## Especificaciones y contratos

- Antes de implementar, consultar la funcionalidad y la carpeta `especificaciones/ES-...` correspondientes en [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs), junto con su `AGENTS.md` y `CONTRIBUTING.md`. Si el repositorio está disponible localmente, se encuentra normalmente en `../G2-Despacho-Specs`.
- Respetar los contratos de [Gestión](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md), [Operación](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-operacion-reparto.md) y las [convenciones externas](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md). Actualizar allí los cambios de contrato, sin duplicarlos en este repositorio.
- Mantener los nombres y comentarios del código en español, siguiendo las convenciones de Specs.

## Cambios y pruebas

- Crear ramas desde `develop` con nombres significativos, sin el prefijo `codex/`. Integrar mediante PR a `develop`; promover las entregas a `main` mediante otro PR.
- Añadir o ajustar pruebas del comportamiento modificado y conservar las comprobaciones de regresión. No deshabilitar pruebas ni debilitar aserciones para ocultar un fallo.
- Ejecutar `./mvnw clean verify` (o `.\mvnw.cmd clean verify` en Windows) dentro de cada servicio afectado. Las pruebas de integración usan Docker y bases desechables con Testcontainers; no apuntarlas a Supabase ni a la base local de trabajo.
- Respetar las restricciones de ejecución indicadas por el usuario. Informar qué se verificó realmente y qué no se ejecutó.

## Datos y configuración

- Cada microservicio es dueño de su base; las integraciones se hacen por API, sin consultar tablas de otro servicio.
- Para cambios nuevos de esquema, agregar una migración Flyway con la siguiente versión disponible. No editar migraciones ya aplicadas a bases persistentes.
- No activar migraciones sobre bases compartidas ni modificar su historial como parte de una prueba local.
- Mantener secretos y `.env` fuera de Git. Conservar las plantillas sin credenciales reales.

## Documentación de la entrega

- Actualizar el README si cambia la forma de ejecutar o configurar el proyecto.
- Actualizar `CHANGELOG.md` únicamente al preparar y publicar una nueva línea base en `main`; no añadir entradas por especificación ni durante el desarrollo de ramas funcionales. En ese corte se consolidan los cambios de la entrega con versión y fecha. Los tags se crean manualmente sobre el commit aprobado de `main`.
