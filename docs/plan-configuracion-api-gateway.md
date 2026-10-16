# Plan de configuración inicial del API Gateway

## Estado del documento

Este documento es un plan técnico provisional. Las rutas, payloads, scopes y códigos HTTP definitivos pertenecen al contrato oficial del repositorio de especificaciones [cite: 1].

La primera implementación está descrita en la [guía de configuración inicial](configuracion-inicial-api-gateway.md). Su código y pruebas están escritos, pero no se han compilado ni ejecutado por indicación del usuario; los criterios de completitud siguen pendientes de verificación.

Para continuar sin un entorno operativo de Seguridad se preparó un [emisor temporal local](seguridad-temporal-local.md). Las pruebas con ese emisor deben identificarse como integración local de G2 y no cierran la integración con las cuentas, clientes y claves reales de G7.

## Objetivo

Configurar una versión mínima del API Gateway que permita validar una integración completa desde un consumidor hasta el microservicio propietario, sin esperar a que toda la lógica del módulo esté implementada.

El resultado inicial esperado es:

```text
Consumidor
    ↓ HTTPS + JWT
API Gateway
    ↓ enrutamiento interno
Microservicio propietario
    ↓
Respuesta contractual simulada o real
```

## Responsabilidad del Gateway

El Gateway será la única entrada pública para los frontends y los módulos consumidores. Tendrá las siguientes responsabilidades:

- Enrutar cada solicitud hacia el microservicio propietario.
- Configurar CORS para los frontends autorizados.
- Validar inicialmente firma, vigencia y audiencia del JWT.
- Propagar el encabezado `Authorization` al microservicio.
- Generar o propagar un identificador de correlación.
- Aplicar límites generales de solicitud cuando corresponda.
- Exponer solamente las rutas públicas acordadas.

El Gateway no contendrá reglas de negocio, no accederá a las bases de datos y no decidirá transiciones del despacho.

## Aplicaciones y puertos locales propuestos

| Aplicación | Puerto local |
|---|---:|
| API Gateway | `8080` |
| Gestión de Despachos | `8081` |
| Operación de Reparto | `8082` |

Las direcciones de los microservicios se configurarán mediante variables de entorno:

```text
GESTION_DESPACHOS_URL
OPERACION_REPARTO_URL
```

En desarrollo apuntarán a `localhost`; en Render utilizarán las direcciones internas de los servicios.

## Plan de implementación

### 1. Cerrar los contratos externos

Antes de implementar una ruta deben acordarse:

- Método y ruta.
- Request y response.
- Códigos HTTP y formato de error.
- Scope requerido.
- Reglas de idempotencia.
- Microservicio propietario.

Se priorizarán los contratos que bloquean a otros módulos:

1. Cotización.
2. Creación de despacho.
3. Cancelación solicitada por Ventas.
4. Seguimiento por pedido.
5. Webhook de estados hacia Ventas.

### 2. Habilitar salud básica

Cada aplicación expondrá solamente los endpoints necesarios de Actuator para comprobar su disponibilidad:

```text
/actuator/health
/actuator/info
```

No se expondrá públicamente el resto de Actuator.

### 3. Configurar el enrutamiento mínimo

La tabla inicial es orientativa y debe ajustarse al contrato definitivo:

| Prefijo público | Destino |
|---|---|
| `/api/v1/cotizaciones/**` | Gestión de Despachos |
| `/api/v1/despachos/**` | Gestión de Despachos |
| `/api/v1/seguimientos/**` | Gestión de Despachos |
| `/api/v1/zonas/**` | Gestión de Despachos |
| `/api/v1/repartidores/**` | Operación de Reparto |
| `/api/v1/furgonetas/**` | Operación de Reparto |
| `/api/v1/jornadas/**` | Operación de Reparto |
| `/api/v1/repartidor/**` | Operación de Reparto |
| `/api/v1/flota/**` | Operación de Reparto |
| `/api/v1/catalogos/motivos-fallo` | Gestión de Despachos |
| `/api/v1/evidencias/**` | Operación de Reparto |

Las comunicaciones privadas entre Gestión y Operación serán directas y no pasarán por el Gateway. El webhook saliente hacia Ventas tampoco se enviará mediante el Gateway [cite: 2].

### 4. Implementar la primera integración vertical

Se recomienda comenzar con cotización por ser una operación sin escritura:

```text
POST /api/v1/cotizaciones
    ↓
API Gateway
    ↓
Gestión de Despachos
    ↓
Respuesta contractual simulada
```

La respuesta inicial puede proceder de un adaptador simulado, pero debe respetar el contrato definitivo. No se colocarán valores fijos directamente en el controlador.

### 5. Incorporar configuración transversal mínima

- CORS limitado a los orígenes configurados.
- Propagación de JWT.
- Identificador de correlación.
- Respuesta controlada cuando un servicio no esté disponible.
- Límites de tamaño de solicitud.
- Registro de solicitudes sin tokens, secretos ni datos personales.

Las fotografías no atravesarán el Gateway. Operación de Reparto autorizará la carga directa al bucket privado de Supabase Storage mediante una URL firmada temporal.

### 6. Integrar Seguridad

El contrato actual de Seguridad y Usuarios ya define el descubrimiento del emisor, los tipos de token, la audiencia de servicio y los scopes. El flujo de integración será:

```text
Consumidor obtiene JWT mediante client_credentials
    ↓
Gateway valida firma, algoritmo, emisor, expiración y tipo; audiencia para servicio
    ↓
Microservicio vuelve a validar JWT y exige el scope o rol y la autorización local
```

Mientras la integración con Seguridad no esté disponible, solo podrá utilizarse una configuración de prueba limitada al ambiente local. No se desplegará públicamente una configuración sin autenticación.

Los tokens humanos con `tipo=acceso` no requieren audiencia según el contrato actual. Los tokens de servicio con `tipo=servicio` deben incluir `api-despacho` en `aud`. El emisor y las claves públicas se obtienen mediante descubrimiento, no de valores ilustrativos del contrato. El perfil `integracion-local` de la implementación conserva la validación JWT.

### 7. Habilitar documentación y pruebas manuales

Springdoc se configurará en los dos microservicios. Swagger UI deberá invocar la URL pública del Gateway y permitir ingresar un token Bearer mediante `Authorize`.

Se verificarán al menos:

- Respuesta exitosa.
- Request inválido.
- Token ausente o inválido.
- Scope insuficiente.
- Servicio interno no disponible.
- Conflictos e idempotencia cuando correspondan.

### 8. Sustituir progresivamente las simulaciones

La arquitectura hexagonal permitirá cambiar el adaptador sin modificar el contrato público:

```text
Inicio:    Controller → caso de uso → adaptador simulado
Final:     Controller → caso de uso → adaptador JPA/Supabase
```

Después de validar cotización se continuará con creación, cancelación, seguimiento, webhook y finalmente los endpoints de los frontends administrativo y de reparto.

## Trabajo posterior

No forma parte de la primera configuración:

- Rate limiting avanzado.
- Circuit breakers sofisticados.
- Trazabilidad distribuida completa.
- Agregación de varias especificaciones OpenAPI.
- Dockerfiles.
- Workflows de CI/CD.

Estos elementos se incorporarán después de comprobar el primer flujo completo.

## Criterio de completitud inicial

La configuración mínima se considerará terminada cuando:

1. Las tres aplicaciones puedan ejecutarse simultáneamente.
2. Sus endpoints de salud respondan correctamente.
3. Una solicitud de cotización ingrese por el Gateway.
4. El Gateway la reenvíe a Gestión de Despachos.
5. Gestión devuelva una respuesta conforme al contrato.
6. La operación pueda probarse con JWT y desde Swagger UI.
7. Existan pruebas automatizadas del enrutamiento y del controlador.

## Referencias

1. [Contrato de integración](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).
2. [Arquitectura de contenedores](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/arquitectura/c4-contenedores.md).
