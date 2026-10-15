# G2 — Backend de Despacho y Entrega a Domicilio

Backend del módulo encargado de planificar, ejecutar y dar seguimiento al despacho de pedidos. El repositorio contiene tres aplicaciones Spring Boot independientes que se compilan y despliegan por separado.

Las reglas funcionales, los estados y los contratos de integración se mantienen en el repositorio [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs) [cite: 1].

## Aplicaciones desplegables

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

Las conexiones a Supabase, los puertos, las rutas del Gateway, la validación JWT y las URLs de otros módulos todavía deben incorporarse a los archivos `application.yaml` mediante variables de entorno. No se deben guardar credenciales ni secretos en Git.

## Pruebas

Las pruebas se ejecutan desde el directorio de cada aplicación:

```powershell
.\mvnw.cmd test
```

Las pruebas de integración de los microservicios pueden requerir que Docker se encuentre iniciado para levantar PostgreSQL mediante Testcontainers.

## API e integraciones

El API Gateway será la única entrada pública para los frontends y los módulos consumidores. Los dos microservicios se comunicarán mediante APIs internas y mantendrán bases de datos aisladas en Supabase.

Operación de Reparto utilizará un bucket privado de Supabase Storage para las fotografías de evidencia. Los archivos se consultarán mediante acceso autorizado o URLs firmadas temporales; en la base de datos solo se conservarán sus referencias y metadatos relevantes [cite: 4].

Los comandos que requieren respuesta inmediata utilizan REST. Los cambios de estado confirmados se notificarán a Ventas y Postventa mediante webhook HTTPS, aplicando bandeja de salida, idempotencia y reintentos [cite: 2].

La documentación interactiva con Springdoc se habilitará al implementar los primeros endpoints. El contrato oficial seguirá estando en `integraciones/api-contract.md` del repositorio de especificaciones.

## Despliegue y CI/CD

Las tres aplicaciones se desplegarán como servicios independientes en Render, indicando como directorio raíz la carpeta correspondiente.

Los Dockerfiles y workflows de GitHub Actions todavía no están implementados. Cuando se incorporen, cada workflow deberá ejecutarse únicamente cuando cambie su aplicación o archivos compartidos relevantes.

El trabajo incremental previsto para el Gateway se encuentra en el [plan de configuración inicial](docs/plan-configuracion-api-gateway.md).

## Contribución

El equipo utiliza ramas de trabajo y Conventional Commits en español:

- Funcionalidad: `feature/f<XX>-<descripcion-corta>`.
- Corrección: `fix/<descripcion-corta>`.
- Documentación: `docs/<descripcion-corta>`.
- Ejemplo de commit: `docs(readme): documentar estructura y ejecución del backend`.

Todo cambio debe ingresar a `main` mediante Pull Request y revisión de al menos un integrante. Las reglas completas se encuentran en la [guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md) [cite: 3].

## Referencias

1. [Repositorio de especificaciones del módulo](https://github.com/Taller-SW-Web/G2-Despacho-Specs).
2. [Contrato de integración](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).
3. [Guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md).
4. [Documentación de Supabase Storage](https://supabase.com/docs/guides/storage).
