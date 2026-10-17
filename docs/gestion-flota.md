# Gestión de flota en Operación de Reparto

## Alcance y trazabilidad

F-05 corresponde a Rhamses según el índice de funcionalidades de las especificaciones [cite: 1]. La implementación está en `servicio-operacion-reparto/flota`, con controladores, casos de uso, puerto y adaptador JDBC.

| Especificación | Comportamiento implementado |
|---|---|
| ES-F05-01 | Registro de repartidor activo y pendiente de vinculación |
| ES-F05-02 | Consulta individual y paginada; filtros de registro, vinculación y turno |
| ES-F05-03 | Edición de contacto, brevete y turno; identidad inmutable |
| ES-F05-04 | Baja lógica, control de carga pendiente y conservación del historial |
| ES-F05-05 | Asociación manual de la cuenta comunicada por Seguridad, conforme al contrato vigente |
| ES-F05-06 | Registro de furgoneta con placa única y capacidad positiva |
| ES-F05-07 | Consulta paginada por estado y coincidencia literal de placa |
| ES-F05-08 | Edición de capacidad maestra sin modificar placa ni instantáneas de jornadas |
| ES-F05-09 | Transiciones de estado y cierre atómico de jornadas vacías |

Las nueve carpetas SDD y el contrato de Operación §4.1 se alinean con registro local y asociación manual de UUID. Seguridad §8.4 conserva la propiedad de cuentas, activación y credenciales [cite: 2]. No se acredita existencia remota de una cuenta solo por recibir su UUID.

No se implementan las pantallas de monitoreo, las APIs de jornada de F-06 ni la ejecución de entregas de F-03. Se agregan las restricciones de persistencia necesarias para que esos futuros escritores no reabran una jornada con recursos inhabilitados.

## Contrato y seguridad

Las rutas pertenecen al contrato de Operación [cite: 3]; los DTO ejecutables y sus restricciones se publican mediante Springdoc cuando `DOCUMENTACION_HABILITADA=true`. No se crea otro contrato de API.

- Las operaciones requieren JWT de acceso y rol `GESTOR_DESPACHO`. Un token de servicio no obtiene ese rol a partir de sus claims.
- Las escrituras requieren `sub` UUID para registrar el actor real; no se inventa un usuario del sistema.
- La página comienza en 1, permite hasta 100 elementos y devuelve contenido, tamaño y totales coherentes dentro de una lectura repetible.
- Los errores usan Problem Details, códigos estables y campos inválidos, sin devolver SQL, contactos ni mensajes internos de PostgreSQL.
- Se rechazan campos ajenos y fracciones en cantidades enteras. DNI, teléfono, correo, tamaños y precisión de capacidades se validan antes de persistir.
- DNI, correo normalizado, placa normalizada e identificador de Seguridad cuentan con restricciones de unicidad en PostgreSQL.
- La API expresa `FUERA_DE_SERVICIO`; el adaptador lo traduce al estado persistido `INACTIVA` del modelo de datos [cite: 4].

## Transacciones y concurrencia

Cada mutación y su auditoría se confirman juntas. La auditoría conserva entidad, identificador, acción, actor, instante y estados anterior/nuevo. Si falla la auditoría se revierte la escritura. Los reintentos de baja, vinculación con la misma cuenta y cambio al mismo estado no duplican auditoría.

La baja y el retiro bloquean las tablas físicas de jornadas, reservas y proyecciones antes de bloquear el recurso. Esto serializa la comprobación con escritores concurrentes, incluidos los que no pasan por F-05. El bloqueo tiene un límite de 10 segundos y el caso de uso un límite de 15 segundos. Es una decisión conservadora para cambios administrativos poco frecuentes; no es una estrategia de escalado para registrar cada entrega.

Se rechaza la operación ante despachos `ASIGNADO` o `EN_CAMINO`, incluso si la jornada histórica figura cerrada. También se rechaza mientras exista una reserva activa: la ausencia de proyección no demuestra que la capacidad pueda liberarse. No se altera ni se libera carga automáticamente.

Al retirar una furgoneta sin carga, o dar de baja un repartidor sin carga, las jornadas activas correspondientes se cierran en la misma transacción, conservando sus capacidades originales. Los triggers impiden nuevas reservas o despachos en curso en una jornada cerrada, y exigen repartidor activo/vinculado y vehículo disponible para abrir o reactivar jornadas.

## Persistencia y Supabase

Existen dos bases independientes. F-05 usa únicamente **Operadores y Flota**, no Servicio Logístico.

### Bases nuevas

Flyway aplica V1 y V2 sobre una base vacía de pruebas o desarrollo. V1 se conserva sin modificar su checksum. V2 incorpora auditoría, reservas y proyecciones locales canónicas utilizadas por las comprobaciones de carga; no implementa sus APIs.

### Esquema heredado desplegado

El esquema observado en Supabase utiliza `furgoneta`, `proyeccion_despacho` y `repartidores.usuario_seguridad_id`. No corresponde a V1. Por eso no se debe activar Flyway ni ejecutar V1 sobre esa base.

El archivo [de adaptación](../operaciones/sql/F05__compatibilidad_operadores_flota.sql) agrega columnas, índices, restricciones, triggers y vistas de compatibilidad, dentro de una sola transacción. Las vistas escribibles proyectan los datos existentes; no se renombran tablas ni se duplican registros. Las fechas históricas de cierre desconocidas se conservan desconocidas.

Las vistas usan `security_invoker`; además se retiran permisos de los roles HTTP públicos de Supabase sobre los nuevos accesos y la función de bloqueo. Los datos se consultan a través del backend autenticado.

La revisión de compatibilidad `1d71b84` registra la validación con rollback y la aplicación previa en Operadores y Flota. **No volver a ejecutarlo**: es una adaptación de una sola aplicación, no una migración idempotente. Servicio Logístico no se modificó.

Para otros entornos heredados, revisar primero las estructuras y sus datos. Con las variables PostgreSQL configuradas fuera de Git, ejecutar desde la raíz del backend:

```powershell
psql --set ON_ERROR_STOP=on --file operaciones/sql/F05__compatibilidad_operadores_flota.sql
```

No se ofrece un borrado automático de los objetos agregados: versiones desplegadas pueden estar utilizándolos. Un rollback de aplicación debe conservar el esquema aditivo.

## Configuración de Render y Vercel

Las [plantillas de variables](../operaciones/render/) contienen marcadores, sin credenciales. Deben trasladarse al panel de variables de cada servicio de Render; Spring no importa archivos `.env` automáticamente.

| Servicio | Perfil | Base o destinos |
|---|---|---|
| Operación | `render` | Operadores y Flota, pooler de región este |
| Gestión | `render` | Servicio Logístico, pooler de región oeste |
| Gateway | `render` | URLs HTTPS independientes de Gestión y Operación |

Los perfiles de los dos servicios mantienen `SPRING_FLYWAY_ENABLED=false` por defecto en Render, desactivan generación de esquema y limitan por defecto el pool JDBC a dos conexiones, sin conexiones mínimas ociosas. La conexión se configura mediante URL privada con `sslmode=require`, usuario y contraseña en Render [cite: 5]. `PORT` lo proporciona la plataforma; el Dockerfile ya lo utiliza [cite: 6].

El perfil del gateway admite los dos orígenes exactos de Vercel y apunta a los servicios Render indicados. Un valor explícito de `CORS_ORIGENES` reemplaza esos valores; deben incluirse los clientes que realmente se publican. Las peticiones del navegador siguen pasando por el gateway.

El backend necesita `SEGURIDAD_URL` real en los tres servicios, con audiencia y algoritmo acordados. La comprobación previa del descubrimiento de `seguridad-ejemplo.onrender.com` devolvió 404; el usuario confirmó que Seguridad aún no está desplegado. No se activa el emisor temporal local en producción ni se omite JWT.

## Verificación histórica del incremento `8f8282a`

- `mvn verify` en Operación: 15 pruebas, sin fallos ni omisiones, y paquete generado.
- Siete pruebas HTTP de F-05 con PostgreSQL real: altas, filtros, paginación, edición, bajas, vinculación, capacidades, estados, validación, permisos y errores.
- Prueba de concurrencia: una reserva no confirmada bloquea el retiro; al confirmarse, el retiro se rechaza y el vehículo permanece disponible.
- Prueba de atomicidad: una caída de auditoría revierte el cambio de contacto.
- Regresión de F-04: conserva su lectura de perfiles y jornadas.
- Prueba del esquema heredado con el mismo archivo SQL operativo y el perfil `render`: escribe en las tablas originales, controla reservas y cierra la jornada.
- `mvn verify` en Gateway: 23 pruebas sin fallos ni omisiones; incluye métodos HTTP, cuerpo, JWT y CORS de ambos clientes Vercel contra un destino HTTP de prueba.
- `mvn verify` en Gestión: 43 pruebas sin fallos ni omisiones, y paquete generado; no se modificaron sus casos de uso.
- Se comprobó por separado que los roles `anon` y `authenticated` no reciben acceso a las vistas de perfiles ni a la función de bloqueo.
- Una prueba adicional ejecutó dos veces los datos sintéticos de la demo F-04 sobre V1/V2: conserva 12 jornadas, incluidas dos cerradas. Los perfiles de demo tienen vínculos ficticios para cumplir las restricciones; no son cuentas reales de Seguridad.
- Los JWT de las pruebas son dobles identificados; estas pruebas no acreditan integración con las cuentas ni claves reales de Seguridad.

Los artefactos y reportes se generan en `target/`, excluido de Git. No se registran secretos, archivos `.env` privados ni datos reales de repartidores.

## Estado de la entrega

La implementación y el esquema se pueden verificar sin el despliegue de Seguridad, pero la prueba autenticada completa desde Vercel queda pendiente de ese servicio externo y de configurar los perfiles/variables en Render. Un push a Git no acredita por sí solo que Render haya desplegado esa revisión; debe verificarse en su panel y con las pruebas autenticadas posteriores.

## Referencias

- [cite: 1] [Índice de funcionalidades F-05](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/funcionalidades/funcionalidad.md).
- [cite: 2] [Contrato externo, integración con Seguridad](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).
- [cite: 3] [Contrato de Operación de Reparto](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-operacion-reparto.md).
- [cite: 4] [Modelo de datos](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/arquitectura/modelo-datos.md).
- [cite: 5] [Conexiones PostgreSQL de Supabase](https://supabase.com/docs/guides/database/connecting-to-postgres).
- [cite: 6] [Despliegue Docker en Render](https://render.com/docs/docker).

## Revisión de alineación F-05

Las rutas `/api/v1/repartidor/**` comprueban antes del controlador el perfil local `ACTIVO` y `VINCULADO` resuelto por el `sub`. Una caída de datos responde `503` sin exponer SQL; perfil ausente o no habilitado responde `403`. La propiedad de cada despacho sigue siendo responsabilidad del caso de uso F-03. No se impone este filtro indiscriminadamente a las rutas compartidas de evidencia que también consume el Gestor.

Se añaden pruebas HTTP de campos inmutables, permisos, recursos ausentes, precisión, cierre de jornada vacía y rechazo de nuevas jornadas con perfil inactivo. También se preparan exportación de respuestas y medición local de páginas de 50; estas salidas solo existen al ejecutar sus pruebas. No se declara cumplimiento del objetivo de 200 ms en Render.

La compilación de producción y pruebas pasó con `mvn -DskipTests test-compile`. La ejecución adicional de PostgreSQL queda pendiente: Docker se mantiene apagado por indicación del usuario. No se ejecutaron las pruebas de limpieza contra Supabase. Las pruebas unitarias usan dobles JDBC/JWT: `mvn -Dtest=ValidadorTokenDespachoTests,ValidadorPerfilRepartidorTests test` aprobó siete pruebas sin errores. El Gateway aprobó `mvn verify` con 23 pruebas; la primera ejecución quedó limitada por los sockets locales del sandbox y la ejecución con permisos locales completó sin errores.

Destinos de Render actualizados:

- Gateway: `https://taller-api-gateway.onrender.com`.
- Gestión: `https://taller-servicio-gestion-despachos.onrender.com`.
- Operación: `https://taller-servicio-operacion-reparto.onrender.com`.

Las variables explícitas del panel de Render prevalecen sobre estos valores por defecto. Gestión también necesita `OPERACION_REPARTO_URL`; ambos servicios usan `GATEWAY_PUBLIC_URL`. No se modificaron variables del panel ni se aplicaron nuevas migraciones en esta revisión.
