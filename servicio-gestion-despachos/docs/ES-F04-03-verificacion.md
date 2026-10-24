# Verificación backend de ES-F04-03

## Alcance de la entrega

Implementación en `servicio-gestion-despachos`, rama `feature/f04-confirmar-recepcion-paquete`, desde `develop` (`a9a8f73`). Fecha: 24/10/2026, America/Lima.

La fuente funcional es [ES-F04-03](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F04-03-ConfirmarRecepcionPaquete/spec.md), con su plan y tareas. El contrato canónico permanece en [Gestión §5.2.1](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md) y [Operación §5.2](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-operacion-reparto.md); este documento registra implementación y evidencia, no otra definición de la API.

Por restricción de esta entrega, no se modifican frontend, Gateway, Seguridad ni Operación de Reparto y Flota. No se declara cerrada toda la especificación: siguen pendientes la interfaz y el recorrido entre servicios reales.

## Implementación

- Validación estricta del cuerpo, selección booleana explícita y observación opcional con recorte de espacios Unicode y límite de 500 puntos de código.
- Recepción, versión, historial y liberación pendiente en una transacción local. El bloqueo por despacho permite revalidar dentro de esa transacción y serializar confirmaciones concurrentes.
- Reconocimiento durable de la solicitud original por despacho/incidencia/versión, actor y huella del contenido normalizado, antes de verificar las precondiciones actuales.
- Conservación del estado fallido y del contador, sin generar evento hacia Ventas.
- Procesador con reclamación SQL atómica, exclusión de 60 segundos, token de procesamiento y HTTP fuera de la transacción; límite de cinco segundos para la llamada completa, incluidas credenciales.
- Reintentos persistidos con calendario configurable. Los avisos de revisión permanecen con frecuencia horaria hasta una confirmación válida.
- Reutilización de la credencial técnica de Gestión y del esquema V1/V2. No se agrega V3 ni se reescriben migraciones.
- Validación del contexto histórico antes de enviar una liberación; una recepción o respuesta original incompleta queda en revisión sin llamadas remotas. Tampoco se devuelve un éxito de repetición a partir de una respuesta original inválida.

La reclamación hace una única escritura autoconfirmada; la petición HTTP empieza después. Solo el token vigente y no vencido puede completar o reprogramar el envío. Una respuesta perdida o una caída antes de guardar la confirmación local se recuperan reclamando la misma solicitud al vencer su exclusión.

## Pruebas y evidencia

Comando de la entrega, desde el directorio del servicio:

```powershell
.\mvnw.cmd clean verify
```

Entorno: Java 21, Maven Wrapper, Docker Desktop y PostgreSQL/PostGIS 17.11 desechable mediante Testcontainers. Flyway crea una base vacía con las migraciones originales V1/V2. Las pruebas no utilizan Supabase ni las bases de Compose. El emisor JWT es un doble de prueba y Operación se representa mediante un servidor HTTP local o MockRestServiceServer; no se acredita su lógica ni la ocupación de su base real.

Verificación final del contenido incluido en este commit: 24/10/2026 a las 01:28:56 (America/Lima), `clean verify`, **BUILD SUCCESS** en 2 min 47 s: **109 pruebas, cero fallos, cero errores y cero omisiones**. Incluye 25 pruebas nuevas: 14 de recepción HTTP con PostgreSQL, 3 de validación, 4 del procesador y 4 del adaptador HTTP. Las demás son regresiones existentes de Gestión. El JAR también se construyó correctamente. Las ejecuciones previas de revisión finalizaron aprobadas; se repitió la suite al reforzar las protecciones de datos históricos.

| Criterio | Comprobación backend | Pruebas principales y límite |
|---|---|---|
| CA-01/02 | Aceptación, sello No sin observación, recepción/historial/versión/solicitud, consulta posterior y confirmación de liberación. | `RecepcionCentroHttpTests`; la disminución real de ocupación en F-06 queda pendiente. |
| CA-03 | Respuesta original inmutable después de liberar, resolver y cambiar la salida actual; normalización del contenido. | `respuestaOriginalSobreviveLiberacionResolucionYNuevaSalida` y `rechazaReutilizacionPorOtroActorOCuerpoPeroNormalizaEspacios`. |
| CA-04 | Conflicto por incidencia, versión, estado y recepción nueva ya existente. | `validaEstadoVersionIncidenciaYReferenciasSinEscribir` y prueba de repetición. |
| CA-05 | Tipos estrictos, extras, parámetros, 500/501 caracteres suplementarios y espacios Unicode. | `SolicitudesRecepcionTests` y `rechazaTiposYParametrosSinMutacion`. |
| CA-06 | Sin token, inválido, rol ajeno y token técnico; credencial técnica exclusiva para la liberación. | `validaAutorizacionAntesDeExistenciaYFormato`, `LiberadorCapacidadHttpTests` y regresión de validadores JWT existentes. |
| CA-07 | Otra salida con otra reserva y reintentos antiguos conservan la reserva original. | Pruebas de respuesta original y recuperación HTTP; receptor de Operación simulado. |
| CA-08 | Fallos inyectados en cada una de las cuatro escrituras, rollback íntegro y referencias incompletas. | `revierteTodasLasEscriturasAnteFalloEnCadaTabla`; PostgreSQL real. |
| CA-09 | Reintentos, pérdida de resultado, recreación del procesador, exclusión, tokens vencidos, errores HTTP y datos incoherentes. | `ProcesadorLiberacionesTests`, `LiberadorCapacidadHttpTests` y pruebas JDBC/HTTP de recuperación. Reinicio de procesos desplegados pendiente. |
| CA-10 | Dos transacciones concurrentes: repetición exacta, otro gestor y contenido diferente del mismo gestor. | Tres carreras de `RecepcionCentroHttpTests`; una recepción/versionado/historial/solicitud. |
| CA-11 | Fixture con NO_INTENTADO, anulación, máximo alcanzado y sin fotografía; recepción independiente de la disponibilidad de Operación. | Prueba de aceptación; Gestión no altera jornadas ni consulta su estado para recibir. La jornada real se verifica con F-06. |
| CA-12/CUI | Pendiente por restricción de alcance. | No se implementa el modal ni se ejecutan pruebas de interfaz. |

Informes reproducibles: `target/surefire-reports/TEST-*.xml` y `target/surefire-reports/*.txt`, generados por Maven y no versionados. Salida de esta ejecución: `target/verificacion-f04.log`, también sin versionar. `clean verify` también construye el JAR del servicio.

## Dependencias que debe comprobar el equipo

1. La incidencia canónica debe conservar la reserva, jornada y repartidor de la salida. Referencias históricas ausentes o parciales reciben error sin inventar datos. Las recepciones antiguas sin solicitud durable requieren reconciliación verificable; esta entrega no fabrica su respuesta original ni las reenvía.
2. Operación debe cumplir su contrato de liberación por reserva, pertenencia al despacho e idempotencia, incluyendo la respuesta original al repetir. Su controlador de liberación ya existe según lectura estática del código; las llamadas de esta entrega prueban únicamente el cliente de Gestión, sin ejecutar ni modificar el servicio receptor.
3. Seguridad debe conceder `operacion:capacidad:liberar` a `modulo-despacho` con audiencia `api-despacho`; las credenciales de producción no se comprobaron ni se modificaron.
4. F-02/F-06 deben impedir una nueva asignación mientras exista la reserva anterior activa y reflejar la ocupación después de liberarla.
5. El frontend debe conectar el modal, recuperar resultados desconocidos y refrescar listado/detalle conforme al contrato. Tampoco se implementan ES-F04-04/05 en esta entrega.

## Correspondencia con las tareas SDD

- T-03: esquema disponible verificado con Flyway en base nueva; no se necesita migración adicional. La reconciliación histórica real sigue pendiente.
- T-04/05: implementación de recepción e idempotencia/concurrencia en Gestión, con pruebas transaccionales.
- T-07: procesador de Gestión y pruebas con receptor HTTP controlado; integración real pendiente.
- T-01/02/08: interfaz fuera del alcance autorizado.
- T-06: Operación fuera del alcance autorizado.
- T-09/10: no cerradas; falta el recorrido real con interfaz y servicios involucrados, además de revisión humana.

No se modifican las casillas del repositorio de especificaciones para atribuir un cierre completo a este incremento backend.
