# Consulta backend de entregas fallidas

La rama `feature/f04-consultar-entregas-fallidas`, nacida de `develop`, implementa la entrada del listado de ES-F04-01 en Gestión. Los lectores JDBC y HTTP, el caso de uso y el controlador se encuentran bajo `incidencias/`. El contrato canónico está en [Gestión §5.1 del repositorio de especificaciones](../../G2-Despacho-Specs/integraciones/api-contract-gestion-despachos.md#51-consultar-entregas-fallidas--es-f04-01); los parámetros y esquemas se mantienen allí.

## Configuración de ejecución

La demo aislada inicia Gateway, Gestión, Operación, dos PostgreSQL y el emisor temporal. Desde la raíz del backend, con Docker Desktop iniciado:

```powershell
docker compose --env-file .env.f04-demo.example -p g2-f04-demo -f compose.yaml -f compose.seguridad-local.yaml -f compose.f04.yaml up --build --wait
./herramientas/f04/Verificar-DemoF04.ps1
```

El script requiere PowerShell 7, prepara únicamente las bases `f04_gestion_demo` y `f04_operacion_demo` y verifica el listado por Gateway. Los SQL tienen una guarda por nombre de base; sus registros son sintéticos, los despachos están marcados como simulados y no representan el flujo de registro de F-03. Para comprobar también una caída y recuperación de Operación, añadir `-ComprobarCaida`. No imprime tokens ni secretos.

La demo usa puertos separados: Gateway `18080`, Swagger de Gestión `18081/swagger-ui.html`, Swagger de Operación `18082/swagger-ui.html` y Seguridad temporal `18090`. Sus JWT tienen firma RS256 verificada mediante descubrimiento/JWKS; el emisor es una herramienta de prueba de G2, no G7. El secreto fijo incluido en Compose es ficticio y exclusivo de esta demo. No se carga el `.env` personal ni se conecta a Supabase.

Para detener solamente este entorno, conservando sus contenedores:

```powershell
docker compose --env-file .env.f04-demo.example -p g2-f04-demo -f compose.yaml -f compose.seguridad-local.yaml -f compose.f04.yaml stop
```

La consulta usa PostgreSQL y se habilita fuera del perfil `integracion-local`. Ese perfil y el Compose actual están destinados a la integración temporal sin base de datos; no habilitan el listado de F-04.

Para ejecutar Gestión con las dependencias reales, configurar en el proceso o IDE:

- La conexión `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` de su base exclusiva.
- `SEGURIDAD_URL` y la audiencia/algoritmo acordados para validar el JWT humano.
- `OPERACION_REPARTO_URL`, origen HTTP(S) de Operación, sin ruta, credenciales ni parámetros. En ejecución directa local el valor predeterminado es `http://localhost:8082`.
- `OPERACION_CLIENT_ID` (predeterminado `modulo-despacho`) y `OPERACION_CLIENT_SECRET`, suministrados al proceso servidor. Gestión obtiene el token mediante Seguridad §8.2, reutiliza el vigente y lo renueva antes de vencer. Solicita `operacion:incidencias:leer`, permiso interno definido en Operación §5.4 que debe concederse al cliente en Seguridad del entorno.
- Como alternativa operativa, `OPERACION_TOKEN_SERVICIO` permite suministrar un JWT técnico externo vigente. Tiene prioridad sobre la obtención automática. Nunca se reenvía el token del Gestor.

El cliente interno limita la conexión a un segundo y la lectura a dos segundos por solicitud; no sigue redirecciones. Sin credencial técnica no hace llamadas anónimas. Si no puede confirmar las jornadas, conserva la consulta normal con la degradación contractual y rechaza la selección de solo atrasados cuando no puede calcularla completa.

La entrada pública es el Gateway, configurado con `GESTION_DESPACHOS_URL` hacia Gestión. Una petición de lectura desde PowerShell, con un JWT humano de prueba autorizado cargado previamente en el proceso, es:

```powershell
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/despachos/fallidos?pagina=1&tamanoPagina=20' `
  -Headers @{ Authorization = "Bearer $env:GESTOR_TOKEN" }
```

La respuesta autorizada lleva `Cache-Control: no-store`. La consulta no escribe recepciones, intentos, estados ni eventos. Los errores de base se normalizan sin exponer SQL o credenciales; las inconsistencias de la referencia vigente o de la política de intentos tienen un error distinto de una lectura no disponible.

El Gestor también puede obtener los motivos para el filtro desde la API canónica de [Gestión §4](../../G2-Despacho-Specs/integraciones/api-contract-gestion-despachos.md#4-catálogo-único-de-motivos-de-fallo). El Gateway dirige esa ruta a Gestión y se consulta la misma tabla `motivos_fallo`, con todos los códigos, incluidos inactivos y `NO_INTENTADO`, ordenados por código. No requiere llamar a Operación para el acceso del Gestor. Una lectura no disponible responde `503` normalizado; el cliente debe ofrecer reintentar.

## Verificación del incremento

Con Java 21, Maven disponible y Docker Desktop iniciado, ejecutar desde cada aplicación:

```powershell
# Desde servicio-gestion-despachos: consulta sobre Flyway real, credencial y regresión F-01
.\mvnw.cmd '-Dtest=ConsultaFallidosHttpTests,ProveedorTokenOperacionTests,FlujoF01Tests' test

# Desde servicio-operacion-reparto: proveedor, PostgreSQL y permisos
.\mvnw.cmd '-Dtest=ConsultaIncidenciasOperativasHttpTests,ValidadorTokenDespachoTests' test
```

`ConsultaFallidosHttpTests` usa ahora las migraciones de producción V1/V2, controlador, caso de uso y JDBC. Mantiene dobles de decodificación JWT y HTTP para comprobar fallos controlados. Las dos pruebas del proveedor usan su V1 y PostgreSQL reales; una prueba nueva verifica obtención/caché/renovación del token. El script de demo ejecuta las tres aplicaciones y ambas bases reales, con Seguridad temporal explícita y sin dobles de los servicios de negocio. La evidencia se conserva en la [carpeta SDD](../../G2-Despacho-Specs/especificaciones/ES-F04-01-ConsultarEntregasFallidas/evidence.md).

## Entrega e integración del equipo

- Gestión incorpora `V2__base_despachos_incidencias.sql` y Operación `V1__base_perfiles_jornadas.sql`, siguiendo las tablas canónicas del modelo. F-02/F-03/F-05/F-06 deben reutilizar esta base al integrar sus escritores; no crear versiones alternativas de esas mismas tablas. Las funciones de creación, asignación, registro del fallo y cierre de jornada no forman parte de esta consulta.
- La referencia vigente tiene una FK compuesta para pertenecer al mismo despacho. Un escritor debe crear la incidencia y actualizar la referencia en su transacción. Las tablas y migraciones ya se prueban con la consulta; el flujo completo generado por los comandos de F-02/F-03 se revisará cuando esos comandos estén disponibles.
- Operación implementa §5.4 con lectura por lotes sobre su propia base, preserva el orden y datos históricos y normaliza ausencias/indisponibilidad. Exige `tipo=servicio`, cliente `modulo-despacho`, audiencia `api-despacho` y scope `operacion:incidencias:leer`; la ruta privada permanece fuera del Gateway.
- Antes de desplegar con G7, configurar sus credenciales, conceder el permiso interno y comprobar el recorrido con su emisor. La prueba local no acredita una concesión ni un despliegue real de Seguridad. No se utilizan las claves ficticias de demo en ese entorno.
- Completar el acceso de repartidores al catálogo compartido con la comprobación de perfil activo de Operación. El acceso del Gestor y la ruta a Gestión ya están implementados; no se concede acceso por un rol ficticio `REPARTIDOR` ni por un identificador enviado por el cliente. Esa integración de campo no es necesaria para el actor Gestor de ES-F04-01, pero el catálogo compartido aún no acredita todos sus consumidores.
- El SQL de `src/test/resources/incidencias/` se conserva solo para pruebas aisladas de datos inconsistentes del lector; no se aplica a las bases del proyecto ni sustituye Flyway.

El frontend de ES-F04-01 y el detalle ES-F04-02 permanecen pendientes. Se avisará a Gerardo antes de comenzar el frontend.
