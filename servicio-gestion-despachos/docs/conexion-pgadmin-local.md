# Conectar pgAdmin al PostgreSQL local

## 1. Objetivo y datos de conexión

Consultar las tablas de las bases locales creadas por el Compose del backend. Los valores siguientes son los predeterminados de desarrollo; un `.env` personalizado puede cambiarlos [cite: 1].

| Dato | Valor predeterminado |
|---|---|
| Base de Gestión | `despacho_gestion` |
| Base de Operación | `despacho_operacion` |
| Usuario PostgreSQL | `despacho_local` |
| Contraseña PostgreSQL local | `despacho-local-no-produccion` |
| Servicio de base de datos en Compose | `postgres` |
| Puerto interno de PostgreSQL | `5432` |
| Puerto publicado en el equipo | `15432`, únicamente en `127.0.0.1` |

Las credenciales para entrar a la interfaz web de pgAdmin son las configuradas en su propia instalación. Las de esta tabla se utilizan al registrar el servidor PostgreSQL.

## 2. Comprobar los contenedores

Desde la raíz del backend:

```bash
docker compose ps
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
```

PostgreSQL debe estar activo y saludable. En la instalación revisada el 02/11/2026, pgAdmin era un contenedor independiente llamado `pgadmin`, publicado en `http://localhost:5050`, y PostgreSQL pertenecía a la red `g2-despacho_default`. pgAdmin no forma parte del Compose de este repositorio.

## 3. Conectar desde pgAdmin ejecutado en Docker

### 3.1. Compartir la red

Consultar las redes actuales:

```bash
docker inspect --format '{{json .NetworkSettings.Networks}}' pgadmin
docker inspect --format '{{json .NetworkSettings.Networks}}' g2-despacho-postgres-1
```

Si pgAdmin todavía no pertenece a `g2-despacho_default`, conectarlo:

```bash
docker network connect g2-despacho_default pgadmin
```

Si ya pertenece a esa red, continuar con el registro del servidor. El comando puede responder que el endpoint ya existe cuando se intenta conectar por segunda vez.

El nombre de la red depende del nombre del proyecto Compose; si se cambió, utilizar el que figure en la inspección de PostgreSQL.

### 3.2. Registrar el servidor

Abrir la interfaz de pgAdmin, en esta instalación `http://localhost:5050`. Seleccionar **Servers → clic derecho → Register → Server**.

En **General**:

| Campo | Valor |
|---|---|
| Name | `Despacho local` |

En **Connection**:

| Campo | Valor |
|---|---|
| Host name/address | `postgres` |
| Port | `5432` |
| Maintenance database | `despacho_gestion` |
| Username | `despacho_local` |
| Password | `despacho-local-no-produccion` |

Para este PostgreSQL local, si pgAdmin tiene SSL obligatorio configurado, seleccionar **SSL mode: Disable** en los parámetros de conexión. Guardar con **Save**.

Dentro del contenedor de pgAdmin, `localhost` apunta al propio pgAdmin. La comunicación por la red compartida utiliza `postgres:5432`; `localhost:15432` corresponde al acceso desde el equipo anfitrión.

### 3.3. Conservar la red al recrear pgAdmin

La conexión manual pertenece al contenedor actual. Si pgAdmin se elimina y recrea, hay que conectarlo otra vez o declarar la red externa en el Compose que administra pgAdmin. Este fragmento se integra con su configuración existente:

```yaml
services:
  pgadmin:
    networks:
      - default
      - despacho

networks:
  despacho:
    external: true
    name: g2-despacho_default
```

Conservar las demás propiedades y redes que ya tenga ese Compose. La red externa debe existir antes de iniciar pgAdmin; el Compose del backend la crea al arrancar. Este fragmento no añade pgAdmin al Compose del backend ni configura sus credenciales o almacenamiento.

## 4. Conectar desde pgAdmin instalado en el equipo

Cuando pgAdmin se ejecuta directamente en el mismo equipo que Docker, registrar el servidor con:

| Campo | Valor |
|---|---|
| Host name/address | `127.0.0.1` |
| Port | `15432` |
| Maintenance database | `despacho_gestion` |
| Username | `despacho_local` |
| Password | `despacho-local-no-produccion` |

Esta conexión utiliza el puerto publicado por Docker. Si se cambió `LOCAL_DB_PORT`, introducir ese valor. El Compose limita este puerto al equipo local, por lo que estos datos no describen una conexión desde otro equipo.

## 5. Abrir las tablas y consultar datos

Expandir:

```text
Servers
└── Despacho local
    └── Databases
        └── despacho_gestion
            └── Schemas
                └── public
                    └── Tables
```

Si faltan objetos en el árbol, usar **Refresh**. Para ver registros: clic derecho sobre una tabla → **View/Edit Data → All Rows**. Para ejecutar SQL, abrir **Query Tool** sobre `despacho_gestion`.

```sql
-- Tablas del esquema público.
SELECT tablename
FROM pg_catalog.pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;

-- Configuración usada para cotizar.
SELECT * FROM public.zonas;
SELECT * FROM public.tarifas_zona;
SELECT * FROM public.zonas_distritos;
SELECT * FROM public.zonas_codigos_postales;
SELECT * FROM public.distrito;

-- Histórico de cotizaciones.
SELECT * FROM public.cotizaciones ORDER BY creado_en DESC;
```

En la implementación revisada, el endpoint de cotización todavía no inserta ese histórico. La tabla `cotizaciones` puede estar vacía aunque Postman haya recibido un resultado exacto. Las zonas y tarifas creadas sí deben aparecer. Ver [plan de persistencia](./plan-persistencia-cotizaciones.md).

Para revisar repartidores, vehículos y jornadas, abrir **Query Tool** sobre `despacho_operacion` y listar allí sus tablas. Cada servicio conserva su propia base; las consultas de esta guía son inspección manual, no una integración entre servicios.

## 6. Comprobar desde la terminal

Para listar tablas sin depender de pgAdmin, desde la raíz del backend:

```bash
docker compose exec postgres psql -U despacho_local -d despacho_gestion -c "\dt public.*"
```

Para consultar zonas:

```bash
docker compose exec postgres psql -U despacho_local -d despacho_gestion -c "SELECT * FROM public.zonas;"
```

Estos comandos comprueban el acceso dentro del contenedor de PostgreSQL; no prueban por sí solos la ruta de red ni la autenticación utilizada por pgAdmin.

## 7. Resolver problemas frecuentes

| Síntoma | Comprobación |
|---|---|
| Conexión rechazada a `localhost` desde pgAdmin en Docker | Utilizar la red compartida y `postgres:5432`. |
| No resuelve el nombre `postgres` | Revisar que ambos contenedores compartan `g2-despacho_default`. |
| No conecta desde la aplicación de escritorio | Revisar PostgreSQL saludable y el puerto publicado con `docker compose ps`. |
| `password authentication failed` | Revisar usuario y contraseña efectivos de esa base. Cambiar `.env` no cambia la contraseña ya almacenada en un volumen inicializado. |
| `database ... does not exist` | Comprobar el nombre de la base y si el volumen fue inicializado con el Compose actual. |
| Error por SSL obligatorio | Para este servidor local, revisar `SSL mode: Disable`. |
| Conectó antes de recrear pgAdmin y ahora falla | Volver a comprobar su pertenencia a la red externa. |
| Hay tablas pero no filas | Una base nueva trae esquema y catálogos, no todos los datos de negocio de otro entorno. |

Si persiste un error, conservar el texto exacto de pgAdmin junto con los nombres de host y puerto utilizados para distinguir red, autenticación y nombre de base.

## 8. Referencias

- [cite: 1] [Compose local](../../compose.yaml), [plantilla de variables](../../.env.example) y [README del backend](../../README.md).
- [Guía de pruebas desde Postman](./pruebas-postman-local.md).
