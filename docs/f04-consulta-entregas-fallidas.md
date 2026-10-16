# Consulta backend de entregas fallidas

La rama `feature/f04-consultar-entregas-fallidas`, nacida de `develop`, implementa la entrada del listado de ES-F04-01 en Gestión. Los lectores JDBC y HTTP, el caso de uso y el controlador se encuentran bajo `incidencias/`. El contrato canónico está en [Gestión §5.1 del repositorio de especificaciones](../../G2-Despacho-Specs/integraciones/api-contract-gestion-despachos.md#51-consultar-entregas-fallidas--es-f04-01); los parámetros y esquemas se mantienen allí.

## Configuración de ejecución

La consulta usa PostgreSQL y se habilita fuera del perfil `integracion-local`. Ese perfil y el Compose actual están destinados a la integración temporal sin base de datos; no habilitan el listado de F-04.

Para ejecutar Gestión con las dependencias reales, configurar en el proceso o IDE:

- La conexión `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` de su base exclusiva.
- `SEGURIDAD_URL` y la audiencia/algoritmo acordados para validar el JWT humano.
- `OPERACION_REPARTO_URL`, origen HTTP(S) de Operación, sin ruta, credenciales ni parámetros. En ejecución directa local el valor predeterminado es `http://localhost:8082`.
- `OPERACION_TOKEN_SERVICIO`, JWT técnico vigente obtenido por el mecanismo acordado con el equipo. Se suministra únicamente al proceso servidor; no se reenvía el token del Gestor. La obtención y renovación automáticas requieren el acuerdo y proveedor de credenciales reales.

El cliente interno limita la conexión a un segundo y la lectura a dos segundos por solicitud; no sigue redirecciones. Sin credencial técnica no hace llamadas anónimas. Si no puede confirmar las jornadas, conserva la consulta normal con la degradación contractual y rechaza la selección de solo atrasados cuando no puede calcularla completa.

La entrada pública es el Gateway, configurado con `GESTION_DESPACHOS_URL` hacia Gestión. Una petición de lectura desde PowerShell, con un JWT humano de prueba autorizado cargado previamente en el proceso, es:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/despachos/fallidos?pagina=1&tamanoPagina=20' `
  -Headers @{ Authorization = "Bearer $env:GESTOR_TOKEN" }
```

La respuesta autorizada lleva `Cache-Control: no-store`. La consulta no escribe recepciones, intentos, estados ni eventos. Los errores de base se normalizan sin exponer SQL o credenciales; las inconsistencias de la referencia vigente o de la política de intentos tienen un error distinto de una lectura no disponible.

El Gestor también puede obtener los motivos para el filtro desde la API canónica de [Gestión §4](../../G2-Despacho-Specs/integraciones/api-contract-gestion-despachos.md#4-catálogo-único-de-motivos-de-fallo). El Gateway dirige esa ruta a Gestión y se consulta la misma tabla `motivos_fallo`, con todos los códigos, incluidos inactivos y `NO_INTENTADO`, ordenados por código. No requiere llamar a Operación para el acceso del Gestor. Una lectura no disponible responde `503` normalizado; el cliente debe ofrecer reintentar.

## Verificación del incremento

Con Java 21, Maven disponible y Docker Desktop iniciado, ejecutar desde cada aplicación:

```powershell
# Desde servicio-gestion-despachos
.\mvnw.cmd test

# Desde api-gateway
.\mvnw.cmd test
```

`ConsultaFallidosHttpTests` usa controlador, seguridad de Spring, caso de uso, JDBC y PostgreSQL reales. El decodificador JWT y la API HTTP de Operación son dobles identificados; los nombres, credenciales y datos son sintéticos. `RutaFallidosGatewayTests` comprueba el Gateway real contra un destino HTTP simulado. La evidencia y los resultados exactos se conservan en la [carpeta SDD](../../G2-Despacho-Specs/especificaciones/ES-F04-01-ConsultarEntregasFallidas/evidence.md).

## Dependencias pendientes para la demo real

- Integrar al final los cambios de `develop` del backend y `main` de especificaciones, conforme a la coordinación de Gerardo. La rama no sustituye las migraciones/escritores compartidos de despachos, incidencias y motivos.
- Comprobar el lector sobre esas migraciones y los registros generados por F-02/F-03. El SQL de `src/test/resources/incidencias/` es exclusivo de las bases temporales de pruebas; no debe aplicarse como migración del proyecto.
- Verificar el proveedor real de Operación §5.4, su autorización técnica y el suministro/renovación de la credencial.
- Completar el acceso de repartidores al catálogo compartido con la comprobación de perfil activo de Operación. El acceso del Gestor y la ruta a Gestión ya están implementados; no se concede acceso por un rol ficticio `REPARTIDOR` ni por un identificador enviado por el cliente. Esa integración de campo no es necesaria para el actor Gestor de ES-F04-01, pero el catálogo compartido aún no acredita todos sus consumidores.
- Ejecutar el recorrido conjunto con Seguridad, Gateway, Gestión, Operación y datos compartidos. Las pruebas con dobles no acreditan esa integración.

El frontend de ES-F04-01 y el detalle ES-F04-02 permanecen pendientes. Se avisará a Gerardo antes de comenzar el frontend.
