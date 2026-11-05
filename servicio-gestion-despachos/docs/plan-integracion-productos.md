# Plan de integración de cotización con Productos y Ofertas

## 1. Estado y objetivo

**Estado:** plan técnico pendiente de implementación, homologación y verificación real. Revisión del código: 02/11/2026.

Conectar la cotización con la API real de Productos para obtener peso y dimensiones vigentes de cada SKU. Marketplace y Chatbot seguirán enviando destino, SKU y cantidad; Gestión consultará Productos con su propia identidad técnica y calculará los totales.

Este documento organiza el trabajo en el backend. Las rutas, cuerpos, unidades, permisos y errores se mantienen en el contrato externo y las especificaciones de Specs [cite: 1, 2]. La homologación pendiente se consulta allí y no se considera aprobada por aparecer en este plan.

## 2. Situación actual comprobada

| Componente | Situación |
|---|---|
| `cotizacion.application.ServicioCotizacion` | Recibe `ProveedorDatosFisicos` sin calificador específico. |
| `cotizacion.infrastructure.CatalogoProductosStub` | Es `@Primary` y está activo fuera de `integracion-local`; por eso la cotización normal lo selecciona. |
| `programacion.infrastructure.ProductosProgramacionHttp` | Ya consulta la ruta física por HTTP, pero su validación y traducción de errores deben ajustarse al contrato vigente. |
| `programacion.infrastructure.ConfiguracionProgramacion` | Crea `productosProgramacion` con URL y credencial propias de Productos. |
| `programacion.application.RecibirSolicitudDespacho` | Selecciona explícitamente `productosProgramacion`; la recepción F-02 ya tiene un camino HTTP diferente al de cotización. |
| `shared.seguridad.ProveedorTokenServicio` | Obtiene y reutiliza el token técnico hasta su renovación; admite un token configurado. |
| `cotizacion.domain.DatosFisicos` | Conserva peso y dimensiones, pero no estado comercial ni `actualizadoEn`. |

Por tanto, configurar `PRODUCTOS_URL` no cambia por sí solo el proveedor elegido en F-01. El trabajo incluye la selección en el contexto de Spring y el contrato del resultado interno, además del transporte HTTP.

## 3. Acuerdos y configuración necesarios

### 3.1. Productos y Seguridad

Confirmar con los equipos proveedores:

- Origen de Productos por entorno y revisión fija del OpenAPI que se probará.
- Operación física canónica: `POST /api/v1/productos/datos-fisicos/consulta`.
- Unidades: peso en kg y dimensiones en cm; la conversión a m³ la realiza Despacho.
- Token de `modulo-despacho` con audiencia `api-productos` y scope `productos:fisicos:leer`.
- Cierre de H-G2-01 a H-G2-03: datos incompletos, estados comerciales e integridad por lote. `BORRADOR` y la forma de comunicar los SKU afectados siguen sujetos a homologación [cite: 1].

El token recibido del canal tiene audiencia de Despacho y no se reenvía a Productos. El emisor temporal local actual no concede `productos:fisicos:leer` ni emite audiencia `api-productos`; no acredita la autenticación real de esta dependencia [cite: 3].

### 3.2. Variables existentes

| Variable | Uso |
|---|---|
| `PRODUCTOS_URL` | Origen HTTP(S) del proveedor, sin añadir la ruta de consulta. |
| `PRODUCTOS_CLIENT_ID` | Cliente técnico; normalmente `modulo-despacho`. |
| `PRODUCTOS_CLIENT_SECRET` | Credencial del cliente, mantenida fuera de Git. |
| `PRODUCTOS_TOKEN_SERVICIO` | Token explícito admitido por el adaptador existente; requiere renovación externa al vencer. |
| `SEGURIDAD_URL` | Origen del emisor que utiliza el proveedor de tokens actual. |

Con `client_credentials`, comprobar que el emisor configurado pueda emitir el permiso de Productos. El Compose local utiliza `seguridad-local`; completar solo `PRODUCTOS_CLIENT_SECRET` no le añade ese permiso. Un token real configurado expresamente permite probar la llamada saliente sin reenviar el JWT del canal, sujeto a las credenciales acordadas para el entorno.

Los valores de ejemplo y su propagación están en [Compose](../../compose.yaml) y la [plantilla del servicio](../.env.example). Si Productos corre en el anfitrión y Gestión en Docker, verificar la resolución de `host.docker.internal` en la instalación Linux; no darla por configurada automáticamente.

## 4. Diseño propuesto

### 4.1. Selección explícita del proveedor

Refactorizar el adaptador existente para compartir transporte y validación física entre F-01 y F-02. Ubicarlo en un paquete de integración compartido por ambos casos de uso, con nombre en español acorde a su responsabilidad.

Definir una selección explícita que permita comprobar que el perfil normal utiliza HTTP real. Restringir el catálogo simulado a una configuración local o de pruebas expresamente habilitada y ajustar Compose y las plantillas para que el recorrido local siga siendo reproducible. El perfil `integracion-local` tiene además un controlador simulado propio; se debe conservar su carácter explícito sin confundirlo con integración real.

No usar el catálogo simulado como sustituto automático ante un fallo HTTP. Si el proveedor real no está disponible, responder con el error de dependencia establecido.

### 4.2. Preparación de la consulta

1. Conservar la autorización y precedencia de cobertura del caso de uso. Sin cobertura o en consulta de solo cobertura, no llamar a Productos.
2. Validar las líneas y agrupar cantidades de los SKU repetidos sin cambiar su identidad, mayúsculas o ceros iniciales.
3. Detectar desbordamiento al sumar cantidades; no permitir que una suma positiva se convierta en un valor negativo o truncado.
4. Rechazar más de 100 SKU únicos antes de HTTP; este límite no se aplica a las unidades ni a las líneas antes de agrupar.
5. Consultar una sola vez el lote admitido con el token propio. No añadir una llamada por producto ni particionar lotes fuera del alcance acordado.

Conservar el comportamiento de zona sin tarifa sin inventar costos; su tratamiento definitivo se debe resolver en la fuente canónica si cambia el orden del flujo existente.

### 4.3. Integridad del lote y datos físicos

Validar toda la respuesta antes de calcular:

- Presencia de `productos` y `noEncontrados`.
- Cada SKU solicitado aparece exactamente una vez en uno de esos arreglos.
- Ausencia de duplicados dentro de cada arreglo y entre ambos, omisiones y SKU inesperados.
- Identidad, estado conocido, peso positivo, tres dimensiones positivas y `actualizadoEn` válido en los productos encontrados.
- JSON y tipos conformes al contrato, sin completar valores faltantes con dimensiones predeterminadas.

Ampliar el resultado interno de la consulta para conservar estado y marca de actualización, además del perfil físico. Esos datos permiten aplicar la política comercial de cada caso de uso y registrar la instantánea prevista en el [plan de persistencia](./plan-persistencia-cotizaciones.md).

La existencia, integridad y validez física son compartidas. La elegibilidad comercial se aplica por caso de uso: F-01 cotiza productos elegibles para venta; F-02 recibe pedidos ya pagados y preparados y no rechaza un SKU solo por estar `INACTIVO`. La extensión a `BORRADOR` requiere aceptación expresa de G6 [cite: 1].

### 4.4. Errores, tiempos y credenciales

Ajustar `ProductosProgramacionHttp` a la matriz canónica, en particular:

| Resultado del proveedor | Tratamiento previsto en Gestión |
|---|---|
| Lote íntegro que clasifica un SKU como no encontrado | Rechazo de negocio por producto inexistente, `422`. |
| Error de datos físicos incompletos homologado | `422`, preservando los SKU afectados. |
| `200` malformado o lote incoherente | `502 DESP_ERROR_RESPUESTA_PRODUCTOS_INVALIDA`. |
| Timeout, conexión fallida o error técnico `5xx` | `503 DESP_ERROR_PRODUCTOS_NO_DISPONIBLE`. |
| `401` o `403` de Productos | Fallo de credencial saliente; informar dependencia no disponible, no atribuirlo al token del canal. |
| Otros errores no contemplados | Clasificar conforme al contrato y la homologación, sin convertir cualquier `422` en inexistencia. |

Validar primero la integridad de todo el lote; no devolver un error parcial de negocio que oculte una respuesta malformada. No calcular ni persistir con parte de los productos.

El cliente existente configura un segundo para conectar y 1.5 segundos para leer. Revisar y acordar estos valores junto con la obtención de tokens y el presupuesto completo de respuesta del Gateway. Probar vencimiento y renovación de credenciales. No agregar reintentos indiscriminados de permisos ni una caché de productos sin un acuerdo sobre su vigencia.

## 5. Incrementos verificables

| Incremento | Entrega | Criterio de salida |
|---|---|---|
| I-01 | Contrato y credenciales del entorno identificados. | Revisión del OpenAPI y homologaciones enlazadas; distinguir pendientes de acuerdos cerrados. |
| I-02 | Adaptador compartido, resultado interno y validador de lote. | Respuestas válidas y no conformes clasificadas sin datos inventados. |
| I-03 | Selección real en F-01 y configuración simulada explícita. | Una prueba de contexto verifica HTTP real en el perfil normal y simulación solo al habilitarla. |
| I-04 | Integración con persistencia y regresión F-02. | El cálculo usa perfiles completos y F-02 conserva idempotencia y su política comercial. |
| I-05 | Recorrido real por Gateway. | Token propio válido, llamada observada en Productos y resultado coherente con los datos del proveedor. |

El trabajo interno puede avanzar con un servidor HTTP controlado. I-05 no se considera aprobado hasta ejecutarlo con Productos y Seguridad del entorno acordado.

## 6. Pruebas y aceptación

La implementación debe añadir o ajustar pruebas de:

- Selección de beans de Spring: F-01 utiliza el adaptador real en el perfil previsto.
- Token entrante del canal distinto del saliente de Despacho; credencial saliente con renovación y audiencia/permisos correctos en el recorrido real.
- Cero llamadas en solo cobertura, destino sin cobertura y exceso de SKU.
- Agrupación y cálculo con 1, 100 y 101 SKU únicos, SKU repetidos y cantidades acumuladas.
- Omisiones, duplicados, inesperados, campos faltantes, valores físicos no positivos y fechas inválidas.
- Errores de negocio homologados, respuestas inválidas, timeout y errores de permisos y servidor.
- Diferencia de elegibilidad entre F-01 y F-02.
- Ausencia de costos o registros parciales y conservación del perfil utilizado en una cotización persistida.
- Regresión de la creación idempotente de despachos: una repetición ya reconocida no vuelve a consultar Productos.

Usar servidores HTTP controlados y bases desechables para automatización. Al implementar, ejecutar dentro de Gestión:

```bash
./mvnw clean verify
```

En Windows, usar `.\mvnw.cmd clean verify`. Verificar también cada otro servicio cuyo código o configuración cambie, conforme a las instrucciones del repositorio. Registrar comandos, revisiones, reportes y dependencias utilizadas en la evidencia SDD. Una prueba simulada no acredita el recorrido real.

## 7. Referencias

- [cite: 1] [Contrato externo](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md), §§5.2, 8 y 11.1: operación física, seguridad y homologaciones.
- [cite: 2] [ES-F01-06](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-06-CotizarEnvioConProductos/spec.md), [plan SDD](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-06-CotizarEnvioConProductos/plan.md) y [ES-F02-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F02-01-RecepcionSolicitudDespacho/spec.md).
- [cite: 3] [Emisor temporal local](../../seguridad-local/src/EmisorSeguridadLocal.java).
- [Guía de Postman con los datos simulados actuales](./pruebas-postman-local.md).
