# Consulta del detalle de una incidencia

Implementación de [ES-F04-02](../../G2-Despacho-Specs/especificaciones/ES-F04-02-ConsultarDetalleIncidencia/spec.md), desde `develop` en `feature/f04-detalle-incidencia` [cite: 1].

## Consulta de Gestión

`ControladorDetalleIncidencia` aplica el contrato de [Gestión §5.3](../../G2-Despacho-Specs/integraciones/api-contract-gestion-despachos.md#53-consultar-el-detalle-de-una-incidencia--es-f04-02). La seguridad existente exige JWT humano y rol de Gestor antes de consultar. `DetalleIncidenciaJdbc` reúne la instantánea con `REPEATABLE_READ`, usando la referencia a la última incidencia incluso en consultas históricas. Lee historial y comunicaciones locales, restringe los campos históricos de presentación y no realiza escrituras.

Después de terminar esa transacción, `ConsultarDetalleIncidencia` reutiliza el lote operativo y limita a cinco segundos el enriquecimiento, incluida la obtención de credencial técnica. Una respuesta ausente conserva nombres y jornadas desconocidos, sin modificar las acciones calculadas localmente.

`V5__consulta_detalle_incidencia.sql` prepara la solicitud durable de liberación que consultan las recepciones confirmadas; no implementa el comando ni su procesador. Una recepción confirmada sin solicitud se informa como inconsistencia, conforme al contrato.

## Evidencia de Operación

El endpoint de [Operación §3.4](../../G2-Despacho-Specs/integraciones/api-contract-operacion-reparto.md#34-autorizar-la-lectura-de-evidencia) permite al Gestor consultar evidencia histórica sin perfil de repartidor; para un repartidor exige perfil activo y propiedad de la proyección local. Solo firma evidencias asociadas. La migración `V3__lectura_evidencias.sql` prepara metadatos y el parámetro vigente de duración; no implementa carga ni asociación de F-03.

`FirmadorSupabase` consume la [API oficial de firma de Supabase Storage](https://supabase.com/docs/reference/self-hosting-storage/generate-a-presigned-url-to-retrieve-an-object), según el proveedor propuesto en C4. Configurar `EVIDENCIAS_STORAGE_URL` como origen HTTPS, `EVIDENCIAS_BUCKET` privado y `EVIDENCIAS_CLAVE_SERVICIO` exclusivamente en Operación. El almacenamiento comprueba la existencia al firmar; el adaptador valida ruta, origen y vencimiento de la respuesta. No guarda ni registra URLs temporales. Sin configuración, la lectura responde con indisponibilidad, sin impedir otras funcionalidades.

## Verificación local y pendientes

Se añadieron nueve pruebas: cuatro de Gestión y cinco de Operación. Cubren política combinada, degradación operativa, protección del historial, autorización HTTP y de evidencia, objeto eliminado y firma temporal con proveedor simulado. Se ejecutan sin Docker; los tests HTTP usan MockMvc y los proveedores de persistencia/almacenamiento son dobles identificados.

Desde cada servicio, ejecutar Maven con `-Dtest=ConsultarDetalleIncidenciaTests,DetalleIncidenciaJdbcTests,ControladorDetalleIncidenciaTests test` o `-Dtest=AutorizarLecturaEvidenciaTests,FirmadorSupabaseTests,ControladorLecturaEvidenciaTests test`. Se utilizó Maven local 3.9.16 porque el wrapper de Gestión solicita 3.10.0 y no estaba disponible localmente. La dependencia nueva del `develop` de dotenv se descargó sin modificar el POM.

Antes de validar PostgreSQL, revisar la colisión heredada entre `V3__programacion_despachos.sql` y `V3__zonas_codigos_postales.sql`, consultando el historial Flyway de cada entorno. No se cambian numeraciones ni checksums aplicados sin esa información. Las migraciones nuevas no se ejecutaron en esta entrega, por indicación de Gerardo.

Quedan para la integración real: historial producido por F-02/F-03/F-04, configuración privada de Storage, identidades reales, migraciones PostgreSQL y recorridos de los comandos ES-F04-03/04/05. El código de consulta no simula esos comandos ni reenvía eventos [cite: 1].

## Referencias

- [cite: 1] [Plan](../../G2-Despacho-Specs/especificaciones/ES-F04-02-ConsultarDetalleIncidencia/plan.md), [tareas](../../G2-Despacho-Specs/especificaciones/ES-F04-02-ConsultarDetalleIncidencia/tasks.md) y [evidencia](../../G2-Despacho-Specs/especificaciones/ES-F04-02-ConsultarDetalleIncidencia/evidence.md).
