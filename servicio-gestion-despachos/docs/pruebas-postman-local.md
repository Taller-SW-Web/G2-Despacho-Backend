# Probar la cotización local desde Postman

## 1. Objetivo y alcance

Levantar el entorno local, preparar una zona con tarifa y solicitar una cotización como Marketplace o Chatbot. Los cuerpos de esta guía son ejemplos de prueba; el contrato canónico se mantiene en Specs [cite: 1].

En la implementación revisada el 02/11/2026, la cotización utiliza `CatalogoProductosStub` y todavía no escribe su resultado en `cotizaciones`. Los planes de [persistencia](./plan-persistencia-cotizaciones.md) e [integración con Productos](./plan-integracion-productos.md) describen esos pendientes; una respuesta exitosa de esta guía no acredita que estén implementados.

## 2. Levantar el entorno

Requisitos: Docker con Docker Compose y Postman. Docker compila Java dentro de las imágenes.

Desde la raíz del backend, donde está `compose.yaml`:

```bash
docker compose up --build -d
```

El Compose inicia Gateway, Gestión, Operación, Seguridad temporal y PostgreSQL con dos bases locales separadas. Funciona sin crear un `.env`; si ya existe uno, sus valores pueden cambiar los puertos y las credenciales de esta guía [cite: 2].

Comprobar el estado y, si hace falta, los registros:

```bash
docker compose ps
docker compose logs --tail=100 servicio-gestion-despachos servicio-operacion-reparto api-gateway
```

Esperar a que los servicios estén saludables. La primera construcción descarga imágenes y dependencias. El volumen conserva los datos de arranques anteriores; una base nueva no trae zonas ni tarifas de negocio.

## 3. Preparar Postman y comprobar salud

Crear una colección llamada **Cotización local**. En su pestaña **Variables**, guardar:

| Variable | Valor inicial |
|---|---|
| `baseUrl` | `http://localhost:8080` |
| `seguridadUrl` | `http://localhost:8090` |
| `tokenGestor` | Vacío |
| `tokenServicio` | Vacío |
| `idZona` | Vacío |

Guardar las solicitudes siguientes dentro de esa colección para que resuelvan sus variables.

Crear una solicitud con **Authorization → No Auth**:

```http
GET {{baseUrl}}/actuator/health
```

Esperar `200 OK` con `status: "UP"`. La salud confirma disponibilidad informada por la aplicación, no todos los recorridos de negocio.

## 4. Obtener el token de gestor

El gestor configura zonas y tarifas. Crear:

```http
POST {{seguridadUrl}}/pruebas/token-usuario
```

- **Authorization:** `No Auth`.
- **Body:** `x-www-form-urlencoded`.

| Key | Value |
|---|---|
| `perfil` | `gestor` |

Enviar y copiar únicamente el texto de `accessToken`, sin comillas ni el prefijo `Bearer`, en la variable `tokenGestor`. El emisor local devuelve también `tokenType`, `expiresIn` y `entorno` [cite: 3].

## 5. Consultar o crear una zona

Primero consultar si ya hay una zona para el distrito:

```http
GET {{baseUrl}}/api/v1/zonas?distrito=San%20Borja
```

En **Authorization → Bearer Token**, introducir `{{tokenGestor}}`.

Si existe una zona activa adecuada, copiar su `id` desde `contenido` a `idZona`. Consultar su tarifa con la solicitud de la sección 6 y reutilizarla si sirve para la prueba. Los importes de la sección 8 suponen exactamente la tarifa del ejemplo.

Si no existe, crear:

```http
POST {{baseUrl}}/api/v1/zonas
```

- **Authorization → Bearer Token:** `{{tokenGestor}}`.
- **Body → raw → JSON:**

```json
{
  "nombre": "Lima Centro Postman",
  "estado": "ACTIVO",
  "distritos": [
    {
      "distrito": "San Borja",
      "codigosPostales": ["15036"]
    }
  ]
}
```

Esperar `201 Created` y guardar el campo `id` de la respuesta en `idZona`. Un `409` indica un nombre duplicado o cobertura solapada; consultar las zonas existentes antes de volver a intentar.

## 6. Consultar o configurar la tarifa

Para consultar la tarifa de la zona elegida:

```http
GET {{baseUrl}}/api/v1/zonas/{{idZona}}/tarifa
```

Usar **Authorization → Bearer Token:** `{{tokenGestor}}`. Un `404` con `DESP_ERROR_TARIFA_NO_ENCONTRADA` indica que la zona no tiene tarifa vigente.

Para crear o reemplazar la tarifa en esta base de pruebas:

```http
PUT {{baseUrl}}/api/v1/zonas/{{idZona}}/tarifa
```

- **Authorization → Bearer Token:** `{{tokenGestor}}`.
- **Body → raw → JSON:**

```json
{
  "tarifaBase": 10,
  "pesoIncluidoKg": 3,
  "recargoKgAdicional": 2,
  "factorVolumetrico": 1.25,
  "moneda": "PEN",
  "plazoEstimadoDiasHabiles": 2
}
```

Esperar `200 OK`. Guardar una tarifa reemplaza la vigente y conserva la anterior como histórico.

## 7. Obtener el token de servicio

La cotización requiere identidad técnica del canal, no el token del gestor:

```http
POST {{seguridadUrl}}/api/v1/auth/token
```

- **Authorization:** `No Auth`.
- **Body:** `x-www-form-urlencoded`.

| Key | Value |
|---|---|
| `grant_type` | `client_credentials` |
| `client_id` | `modulo-marketplace` |
| `client_secret` | `despacho-local-no-produccion` |
| `scope` | `cotizaciones:calcular` |

La contraseña indicada es ficticia y pertenece al emisor local. Copiar `accessToken` a `tokenServicio`. Para probar Chatbot, repetir con `client_id=modulo-chatbot` y guardar el nuevo token en la misma variable.

## 8. Solicitar una cotización con productos

```http
POST {{baseUrl}}/api/v1/cotizaciones
```

- **Authorization → Bearer Token:** `{{tokenServicio}}`.
- **Body → raw → JSON:**

```json
{
  "destino": {
    "direccion": "Av. Javier Prado Este 2465",
    "distrito": "San Borja",
    "codigoPostal": "15036"
  },
  "lineas": [
    { "sku": "POL-NEG-M", "cantidad": 5 },
    { "sku": "ZAP-RUN-42", "cantidad": 1 }
  ]
}
```

Postman establece `Content-Type: application/json` al elegir JSON. Evitar encabezados `Authorization` duplicados.

Con la zona y tarifa del ejemplo, esperar `200 OK`. Entre los campos de la respuesta deben aparecer:

```json
{
  "coberturaDisponible": true,
  "tipoCotizacion": "EXACTA",
  "nombreZona": "Lima Centro Postman",
  "pesoTotalKg": 2.65,
  "volumenTotalM3": 0.021,
  "costoEnvio": 10,
  "moneda": "PEN",
  "plazoEstimadoDiasHabiles": 2
}
```

Este fragmento no es la respuesta completa: también se devuelven datos del destino, `fechaEstimadaEntrega` y `cotizadoEn`. Las fechas se calculan al ejecutar la solicitud.

Los datos predeterminados del catálogo simulado son:

| SKU | Peso unitario | Dimensiones unitarias |
|---|---|---|
| `POL-NEG-M` | 0.25 kg | 10 × 10 × 10 cm |
| `ZAP-RUN-42` | 1.40 kg | 20 × 20 × 40 cm |

Un SKU distinto no recibe dimensiones genéricas: debe existir en el catálogo configurado. En el flujo actual, Productos se resuelve después de comprobar cobertura, líneas y tarifa; si se alcanza esa etapa y falta el SKU, se rechaza con `422 DESP_ERROR_PRODUCTO_NO_ENCONTRADO`.

## 9. Probar solo cobertura y validaciones

En la misma solicitud de cotización, conservar el token de servicio y sustituir el cuerpo por:

```json
{
  "destino": {
    "distrito": "San Borja"
  }
}
```

Esperar cobertura disponible sin costo ni plazo. Para comprobar falta de cobertura, usar un distrito que no pertenezca a ninguna zona activa de esta base; se espera `200` con `coberturaDisponible=false`.

Otros casos manuales:

| Caso | Resultado esperado |
|---|---|
| Cuerpo `{}` | `400 DESP_ERROR_DESTINO_REQUERIDO` |
| Destino cubierto y `lineas: []` | `400 DESP_ERROR_LINEAS_VACIAS` |
| Destino cubierto y cantidad `0` | `400 DESP_ERROR_CANTIDAD_INVALIDA` |
| Token de gestor al cotizar | `403` |
| Sin token | `401` |
| Zona activa sin tarifa | Cobertura disponible sin costo ni plazo |

La cobertura tiene precedencia: sin cobertura no se validan las líneas ni se consultan productos.

## 10. Ver los registros y resolver problemas

Las zonas y tarifas creadas se pueden consultar mediante la [guía de pgAdmin](./conexion-pgadmin-local.md). En el código revisado, `cotizaciones` puede permanecer vacía aunque se reciba una cotización exacta exitosa; implementar su escritura es el pendiente descrito en el plan de persistencia.

| Problema | Comprobación |
|---|---|
| No conecta a `8080` | Revisar `docker compose ps`, los registros y `GATEWAY_PORT`. |
| `401` con token | Copiar el token completo, revisar vencimiento y volver a emitirlo. |
| `403` en zonas o tarifa | Usar `tokenGestor`. |
| `403` en cotización | Usar `tokenServicio` con `cotizaciones:calcular`. |
| Cobertura falsa | Revisar distrito, código postal y estado de la zona. |
| Cobertura sin costo | Comprobar tarifa vigente y envío de `lineas`. |
| `422` por producto | Usar SKU presentes en el catálogo simulado. |

El token de gestor dura 15 minutos y el de servicio una hora. Si se reinicia Seguridad temporal, obtener tokens nuevos y reiniciar Gateway/Gestión/Operación si conservan las claves anteriores en caché [cite: 3].

Para detener el entorno conservando los datos y volver a iniciarlo:

```bash
docker compose stop
docker compose up -d
```

Los datos se conservan en el volumen local. `docker compose down -v` lo elimina. Construir con Compose no ejecuta las pruebas automatizadas de Maven.

## 11. Referencias

- [cite: 1] [Contrato externo: cotización y autenticación](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md), §§5.1 y 8. [Contrato de Gestión](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md), §3.1.
- [cite: 2] [Compose local](../../compose.yaml), [plantilla de variables](../../.env.example) y [README del backend](../../README.md).
- [cite: 3] [Emisor de Seguridad temporal](../../seguridad-local/src/EmisorSeguridadLocal.java).
