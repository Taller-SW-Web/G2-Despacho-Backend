# G2 — Backend de Despacho y Entrega a Domicilio

Backend del módulo encargado de planificar, ejecutar y dar seguimiento al despacho de pedidos. El repositorio contiene tres aplicaciones Spring Boot independientes que se compilan y despliegan por separado.

Las reglas funcionales, los estados y los contratos de integración se mantienen en el repositorio [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs) [cite: 1].

## Estado temporal de la integración con Seguridad

> **Aviso para el equipo y los módulos consumidores:** se dispone de un emisor local de pruebas de G2 para avanzar sin un entorno operativo de Seguridad. No es el servicio real de G7. La integración con las cuentas, credenciales y claves reales permanece pendiente.

El modo temporal conserva la validación JWT y los permisos implementados, utilizando claves y tokens ficticios RS256. No se activa automáticamente si Seguridad falla: hay que seleccionarlo con `compose.seguridad-local.yaml`. El modo real requiere una URL configurada y no omite la autenticación.

La [guía de seguridad temporal](docs/seguridad-temporal-local.md) explica cómo iniciar el entorno, obtener tokens para Swagger y otros consumidores, registrar las limitaciones y migrar a Seguridad real. La [guía de flota](docs/gestion-flota.md) registra las verificaciones ejecutadas del backend. La integración con cuentas y claves reales de Seguridad sigue pendiente de su despliegue.

## Aplicaciones desplegables

La implementación F-05 de Rhamses, sus pruebas y la adaptación del esquema de Operadores y Flota están descritas en la [guía de gestión de flota](docs/gestion-flota.md). Incluye las variables para Render, los orígenes de Vercel y la dependencia pendiente del despliegue real de Seguridad.

| Aplicación | Responsabilidad principal | Stack web |
|---|---|---|
| `api-gateway` | Punto de entrada público, enrutamiento, CORS, seguridad inicial y correlación de solicitudes | Spring Cloud Gateway WebFlux |
| `servicio-gestion-despachos` | Zonas, tarifas, cotización, estado del despacho, asignación, incidencias, seguimiento y eventos hacia Ventas | Spring Web MVC |
| `servicio-operacion-reparto` | Repartidores, vehículos, jornadas, capacidad, rutas y evidencias de entrega | Spring Web MVC |

Cada microservicio es propietario de sus datos. No se comparten tablas ni entidades JPA entre Gestión de Despachos y Operación de Reparto.

## Tecnologías principales

- Java 21.
- Maven Wrapper.
- Spring Boot 4.1.1.
- Spring Cloud 2025.1.3 para el API Gateway.
- Spring Security y OAuth2 Resource Server.
- Spring Data JPA en los microservicios.
- PostgreSQL 17.11 administrado por Supabase y Flyway.
- Supabase Storage con bucket privado para evidencias de entrega.
- Testcontainers para pruebas de integración.
- Springdoc OpenAPI para documentar las APIs.
- Hibernate Spatial y JTS en Gestión de Despachos para zonas geográficas.

## Estructura del repositorio

```text
G2-Despacho-Backend/
├── compose.yaml
├── compose.seguridad-local.yaml
├── .env.example
├── herramientas/seguridad-local/
├── api-gateway/
├── servicio-gestion-despachos/
└── servicio-operacion-reparto/
```

No existe un proyecto Maven raíz: cada directorio posee su propio `pom.xml` y Maven Wrapper. Esto permite construir, probar y desplegar cada aplicación de manera independiente.

## Arquitectura interna

Los microservicios se organizarán por funcionalidad y aplicarán una arquitectura hexagonal pragmática. Cada funcionalidad podrá contener las siguientes áreas cuando sean necesarias:

```text
funcionalidad/
├── api/               # Controladores y DTO
├── application/       # Casos de uso
├── domain/            # Entidades y reglas de negocio
└── infrastructure/    # JPA, HTTP, Storage y configuración
```

Los controladores son adaptadores de entrada. PostgreSQL, los clientes HTTP, el almacenamiento de evidencias y los webhooks son adaptadores de salida. Las interfaces o puertos mantienen los casos de uso separados de esas tecnologías.

El paquete `shared` se reserva para componentes transversales del microservicio, como el manejo uniforme de errores, seguridad, auditoría y configuración común. No debe utilizarse como depósito de lógica funcional.

## Requisitos

- JDK 21.
- Git.
- Docker para ejecutar las pruebas que utilizan Testcontainers.
- Dos persistencias PostgreSQL aisladas en Supabase: una para Gestión de Despachos y otra para Operación de Reparto.

No es necesario instalar Maven globalmente porque cada proyecto incluye Maven Wrapper.

## Ejecución local

### Con Docker Compose

La configuración de Compose permite iniciar las tres aplicaciones desde la raíz del repositorio. Cada aplicación tiene su propio Dockerfile y el `.env` raíz contiene la configuración local, fuera de Git. La plantilla compartida es `.env.example`.

En este checkout ya se creó `.env`; en otro clon, copiar primero `.env.example` como `.env`. Con Docker Desktop iniciado, para la integración temporal sin URL ni cuentas reales de Seguridad:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml up --build -d
```

Ese comando construye e inicia los cuatro contenedores: Gateway, Gestión, Operación y emisor temporal. `-d` los deja en segundo plano; se puede omitir para ver los registros durante el arranque. Para consultar su estado y después seguir sus registros:

```powershell
docker compose -f compose.yaml -f compose.seguridad-local.yaml ps
docker compose -f compose.yaml -f compose.seguridad-local.yaml logs -f
```

`logs -f` solo muestra registros de contenedores existentes: no construye imágenes ni inicia el backend. `Ctrl+C` deja de seguir los registros, pero no apaga los servicios iniciados con `-d`.

Para utilizar Seguridad real, completar `SEGURIDAD_URL` y ejecutar `docker compose up --build` sin el archivo adicional. No es necesario que el emisor real esté en la nube: puede ser un backend local accesible. Usar los mismos archivos Compose al consultar registros o detener el entorno seleccionado.

Los dos microservicios utilizan `integracion-local`, sin PostgreSQL y con JWT habilitado. El Gateway queda en `http://localhost:8080` y Swagger de Gestión en `http://localhost:8081/swagger-ui.html`. La [guía de ejecución con Docker](docs/ejecucion-con-docker.md) detalla variables, salud, registros, apagado y comprobaciones pendientes.

Los archivos Docker están preparados, pero todavía no se han construido imágenes ni ejecutado contenedores.

### Con Maven

Cada aplicación se inicia en una terminal diferente. En Windows PowerShell:

```powershell
cd api-gateway
.\mvnw.cmd spring-boot:run
```

```powershell
cd servicio-gestion-despachos
.\mvnw.cmd spring-boot:run
```

```powershell
cd servicio-operacion-reparto
.\mvnw.cmd spring-boot:run
```

Antes de ejecutar estas instrucciones, configurar `SEGURIDAD_URL` con el origen real de Seguridad. Los puertos locales ya están definidos como 8080, 8081 y 8082. Cada aplicación incluye un `.env.example` como referencia; estos archivos no se cargan automáticamente.

Para la primera integración, los microservicios disponen del perfil `integracion-local`, que habilita Swagger UI y permite arrancar sin PostgreSQL. En Gestión también habilita una cotización simulada protegida por JWT. Fuera de ese perfil se requiere configurar la conexión de base de datos propia de cada microservicio mediante las variables habituales de Spring Boot. No se deben guardar credenciales ni secretos en Git.

La [guía de configuración inicial](docs/configuracion-inicial-api-gateway.md) explica las variables, las rutas, el alcance de la simulación y las comprobaciones pendientes. El código de esta entrega todavía no se ha compilado ni ejecutado.

## Pruebas

Las pruebas se ejecutan desde el directorio de cada aplicación:

```powershell
.\mvnw.cmd test
```

Las pruebas de integración de los microservicios pueden requerir que Docker se encuentre iniciado para levantar PostgreSQL mediante Testcontainers.

El avance backend de ES-F04-01, su configuración de ejecución y las dependencias pendientes para la demo se describen en la [guía de consulta de entregas fallidas](docs/f04-consulta-entregas-fallidas.md). El listado requiere PostgreSQL y no se habilita en `integracion-local`.

## API e integraciones

El formato agrupado de zonas por distrito y códigos postales, el padrón de
Lima/Callao y sus pruebas breves se describen en la
[guía F-01 de zonas y códigos postales](docs/f01-zonas-codigos-postales.md).

El API Gateway será la única entrada pública para los frontends y los módulos consumidores. Los dos microservicios se comunicarán mediante APIs internas y mantendrán bases de datos aisladas en Supabase.

Operación de Reparto utilizará un bucket privado de Supabase Storage para las fotografías de evidencia. Los archivos se consultarán mediante acceso autorizado o URLs firmadas temporales; en la base de datos solo se conservarán sus referencias y metadatos relevantes [cite: 4].

Los comandos que requieren respuesta inmediata utilizan REST. Los cambios de estado confirmados se notificarán a Ventas y Postventa mediante webhook HTTPS, aplicando bandeja de salida, idempotencia y reintentos [cite: 2].

La documentación interactiva con Springdoc está configurada en ambos microservicios y apunta a la URL del Gateway. Está deshabilitada por defecto y se habilita con `integracion-local` o `DOCUMENTACION_HABILITADA=true`. El contrato oficial sigue estando en `integraciones/api-contract.md` del repositorio de especificaciones.

## Despliegue y CI/CD

Las tres aplicaciones se despliegan como servicios independientes en Render, indicando como directorio raíz la carpeta correspondiente.

Cada aplicación incluye un Dockerfile independiente. El archivo Compose de la raíz está destinado a integración local. Los perfiles y las plantillas de variables de Render están descritos en la [guía de flota](docs/gestion-flota.md); la configuración de cada servicio y la revisión efectivamente desplegada deben comprobarse en Render. Los workflows de GitHub Actions deberán ejecutarse únicamente cuando cambie su aplicación o archivos compartidos relevantes.

El trabajo incremental previsto para el Gateway se encuentra en el [plan de configuración inicial](docs/plan-configuracion-api-gateway.md).

## Contribución

El equipo utiliza ramas de trabajo y Conventional Commits en español:

- Funcionalidad: `feature/f<XX>-<descripcion-corta>`.
- Corrección: `fix/<descripcion-corta>`.
- Documentación: `docs/<descripcion-corta>`.
- Ejemplo de commit: `docs(readme): documentar estructura y ejecución del backend`.

Para este backend, `develop` será la rama de integración del equipo y `main` conservará las versiones estables y verificadas. El flujo acordado es:

1. Crear las ramas de trabajo desde `develop` actualizada.
2. Abrir los Pull Requests de funcionalidades, correcciones y documentación hacia `develop`, con revisión de al menos otro integrante.
3. Tras verificar la integración y las pruebas, abrir un Pull Request de `develop` hacia `main` para preparar una versión estable.

La rama `feature/seguridad-local-temporal` acumula la base del Gateway, Docker y el emisor temporal; su Pull Request inicial debe tener `develop` como destino. `develop` se crea desde `main` antes de integrar esos cambios mediante el PR.

Las convenciones generales se encuentran en la [guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md) [cite: 3]. Esa guía todavía describe ramas y PR directamente desde/hacia `main`; para el backend se aplica el flujo con `develop` descrito aquí. Las protecciones de ambas ramas deben configurarse en GitHub: crear una rama local no las activa.

## Referencias

1. [Repositorio de especificaciones del módulo](https://github.com/Taller-SW-Web/G2-Despacho-Specs).
2. [Contrato de integración](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).
3. [Guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md).
4. [Documentación de Supabase Storage](https://supabase.com/docs/guides/storage).
