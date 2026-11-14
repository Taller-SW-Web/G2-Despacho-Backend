# G2 — Backend de Despacho y Entrega a Domicilio

Gateway, Gestión de Despachos y Operación de Reparto y Flota son aplicaciones Spring Boot independientes. Las aplicaciones web y los módulos externos consumen las APIs públicas a través del Gateway. Gestión y Operación tienen bases propias.

## Arranque local

Con Docker Desktop iniciado y contenedores Linux en una máquina compatible con la imagen PostGIS `amd64`, ejecutar desde la raíz:

```powershell
docker compose up -d --build
docker compose ps
```

Compose compila con Java 21 dentro de las imágenes y arranca cinco contenedores. La primera construcción descarga imágenes y dependencias. Se puede añadir `--wait` al comando de arranque para esperar a que pasen las comprobaciones de salud.

| Servicio | Dirección predeterminada |
|---|---|
| API Gateway | `http://localhost:8080` |
| Gestión de Despachos | `http://localhost:8081/swagger-ui.html` |
| Operación de Reparto y Flota | `http://localhost:8082/swagger-ui.html` |
| Seguridad temporal | `http://127.0.0.1:8090` |
| PostgreSQL/PostGIS | `localhost:15432` |

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose logs --tail=100 api-gateway servicio-gestion-despachos servicio-operacion-reparto
```

`docker compose stop` detiene los contenedores. `docker compose down` elimina contenedores y red y conserva el volumen. Añadir `-v` elimina también los datos locales de este proyecto.

## Variables locales

Compose usa los valores de [compose.yaml](./compose.yaml) y lee automáticamente el `.env` de la raíz si existe. Usar [.env.example](./.env.example) como plantilla para cambiar puertos o configurar integraciones. Las variables de la terminal pueden prevalecer. Para un arranque con la plantilla explícita se puede ejecutar `docker compose --env-file .env.example up -d --build`.

Las aplicaciones del frontend se ejecutan aparte y tienen sus propios archivos `.env`. Con los puertos predeterminados, ambas apuntan al Gateway en 8080. Ver el [arranque de las dos interfaces](https://github.com/Taller-SW-Web/G2-Despacho-Frontend#arranque-local).

## Bases y migraciones

En el primer arranque con un volumen vacío, [init-db.sql](./init-db.sql) crea `despacho_gestion` y `despacho_operacion` en el PostgreSQL local. Flyway aplica **V1–V11 en Gestión** y **V1–V7 en Operación** en esta rama. Una base nueva incluye catálogos y esquema; las zonas, tarifas, perfiles, vehículos y despachos de prueba se preparan según el recorrido.

Para comprobar las migraciones con el usuario local predeterminado:

```powershell
docker compose exec postgres psql -U despacho_local -d despacho_gestion -c "SELECT version, description, success FROM public.flyway_schema_history ORDER BY installed_rank;"
docker compose exec postgres psql -U despacho_local -d despacho_operacion -c "SELECT version, description, success FROM public.flyway_schema_history ORDER BY installed_rank;"
```

El volumen local se reutiliza entre arranques. Para bases persistentes y ajustes de Flyway, consultar la [guía de despliegue](./docs/despliegue.md#configuración-de-los-servicios).

## Seguridad temporal y pruebas manuales

El emisor local usa cuentas ficticias y firma JWT de prueba. El gestor `gestor@despacho.pe` accede con MFA y el buzón simulado de Vite. Los repartidores `gerardo@despacho.pe` y `kevin@despacho.pe` necesitan un perfil activo vinculado y una jornada. La clave local de las tres cuentas es `Ruta2026!`.

- **Recorrido operativo:** registrar zona, repartidor y vehículo, abrir una jornada y asignar un despacho simulado. El cierre de jornada registra los pendientes como `FALLIDO` con motivo `NO_INTENTADO`. En F-04 se puede confirmar su recepción y cerrarlo como `DEVUELTO_A_ORIGEN`. Para los pasos y datos, consultar la [guía de pruebas](./docs/pruebas.md#recorrido-local-desde-las-apps).
- **Cotización:** Seguridad local emite un token de servicio para Marketplace o Chatbot con `cotizaciones:calcular`. La cobertura se prueba con el Compose normal. Para calcular precio y plazo sin G6, activar el catálogo `productos-simulados` en Gestión y configurar una zona con tarifa. La [guía de Postman](./servicio-gestion-despachos/docs/pruebas-postman-local.md) incluye el override, el token y los cuerpos de ejemplo.
- **Seguimiento por pedido:** consultar el contrato [RT-04](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md#6-rt-04-seguimiento-para-marketplace-y-chatbot) y el [cargador opcional de ejemplos](./pruebas/cargar_seguimientos_local.sql).
- **Dependencias externas:** Gestión usa Productos para cotizar con SKU y recibir pedidos reales, y entrega eventos a Ventas cuando se configura su webhook. Operación necesita un bucket privado para entregar o registrar fallos con foto. Consultar las [variables del entorno](./.env.example) y los [contratos de integración](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md).

## Pruebas y contratos

Con Java 21, ejecutar `.\mvnw.cmd clean verify` en cada servicio afectado en Windows, o `./mvnw clean verify` en Linux. Las pruebas de integración requieren Docker y crean bases desechables con Testcontainers. El arranque de Compose construye las imágenes, mientras que Maven Wrapper ejecuta las suites.

La [guía de pruebas](./docs/pruebas.md) incluye los recorridos F-02 y F-04, el arranque de Gestión para Playwright y cómo distinguir resultados locales de la integración publicada. Para inspeccionar una base local, consultar la [guía de pgAdmin](./servicio-gestion-despachos/docs/conexion-pgadmin-local.md).

La administración de distritos de F-01 dispone de un [recorrido aislado por Gateway y navegador](./docs/pruebas.md#administración-de-distritos-navegador-y-gateway-aislados), con base desechable, Seguridad temporal y Productos simulados.

## Pipeline CI/CD

El [workflow](./.github/workflows/ci.yaml) ejecuta pruebas y construye imágenes. En `main`, la compuerta `CI backend verde` habilita el despliegue en Render y su verificación de salud cuando los deploy hooks están configurados. La [guía de despliegue](./docs/despliegue.md) reúne variables, protección de ramas y diagnóstico.

## Contribuciones

- [Reglas de trabajo](./AGENTS.md) e [historial de entregas](./CHANGELOG.md).
- [Especificaciones, contratos y evidencia](https://github.com/Taller-SW-Web/G2-Despacho-Specs).
