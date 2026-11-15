# Plan de F-01: geometrías y mapas administrativos

**Fecha:** 12/11/2026. **Actualización:** 14/11/2026.

**Estado:** GeoJSON distritales y maquetas preparados localmente; formalización en Specs, validación topológica e implementación pendientes.

**Servicio propietario:** Gestión de Despachos.

**Ámbito:** Lima Metropolitana y Callao; aplicación administrativa de Despacho.

**Objetivo:** incorporar límites geográficos de distritos y mapas administrativos que permitan visualizar, seleccionar e importar geometrías, mientras Marketplace y Chatbot conservan exactamente su contrato actual de cotización.

Este trabajo complementa el [plan de administración de distritos](./plan-f01-administracion-distritos.md). Los requisitos, las decisiones contractuales y la evidencia se formalizarán en [G2-Despacho-Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs). Las operaciones administrativas propuestas aquí son entradas para esa actualización; su definición canónica seguirá en el contrato de Gestión. [cite: 1, 2]

## 1. Alcance de la primera entrega

| Capacidad | Alcance |
|---|---|
| Mostrar distritos de Lima Metropolitana y Callao | Incluido. |
| Visualizar distritos activos e inactivos | Incluido. |
| Mostrar la composición geográfica de las zonas | Incluido. |
| Seleccionar distritos desde el mapa al configurar una zona | Incluido. |
| Sincronizar mapa y selector de distritos | Incluido. |
| Importar archivos GeoJSON desde la pantalla | Incluido. |
| Previsualizar y validar antes de importar | Incluido. |
| Reemplazar geometrías con control de versión e historial | Incluido. |
| Dibujar o modificar vértices en el mapa | Etapa posterior. |
| Resolver cobertura o calcular precios mediante polígonos | Fuera de esta entrega. |

La geometría representará visualmente la configuración. No será una nueva fuente de decisiones para la cotización.

## 2. Protección del contrato con Marketplace y Chatbot

### 2.1. Integración externa

Se mantendrán:

- `POST /api/v1/cotizaciones`.
- La autenticación técnica y el permiso `cotizaciones:calcular`.
- El cuerpo actual: `destino` obligatorio y `lineas` opcionales.
- Los campos, tipos y formatos actuales de respuesta.
- Los códigos y formatos actuales de errores.
- La resolución por distrito y código postal.
- Las reglas de tarifas, peso, volumen, redondeo y plazo.

Los consumidores externos:

- No tendrán que enviar geometrías.
- No tendrán que añadir coordenadas ni campos nuevos.
- No recibirán polígonos en la respuesta de cotización.
- No necesitarán consumir las nuevas APIs administrativas.

La operación externa y sus formatos seguirán definidos exclusivamente en el contrato de integraciones externas. [cite: 3]

### 2.2. Diferencia entre geometría y configuración

| Acción | Efecto sobre cotización |
|---|---|
| Importar una geometría | Ninguno. |
| Reemplazar el límite visual de un distrito | Ninguno. |
| Consultar el mapa | Ninguno. |
| Guardar cambios en las asociaciones de una zona | Aplica la configuración de cobertura guardada. |
| Cambiar una tarifa | Aplica la tarifa vigente, según el comportamiento existente. |
| Desactivar un distrito | Responde sin cobertura, conforme al plan del CRUD confirmado. |

Un distrito sin geometría podrá seguir cotizando si cumple las reglas de cobertura. Un fallo del mapa tampoco afectará al servicio de cotización.

El estado del distrito pertenece al plan del CRUD: su desactivación suspende nuevas cotizaciones y nuevos despachos, conservando los despachos existentes. Importar o reemplazar un `geom` no cambiará ese estado ni las asociaciones de cobertura. [cite: 1]

## 3. Situación técnica actual

Ya existen:

- `distrito.geom`.
- `zonas.geom`.
- Columnas de geometría con SRID `4326`.
- Índices espaciales GiST.
- PostGIS, Hibernate Spatial y JTS.

Falta implementar su carga, consulta y representación administrativa. La existencia de las columnas y las dependencias no acredita que haya geometrías cargadas en un entorno concreto. [cite: 4]

La administración de distritos ya cuenta con implementación y verificación local del 13/11/2026, incluida V11. Esta ampliación utilizará ese catálogo y sus UUID, ubigeos, estados y restricciones de identidad como base. Su evidencia y los pendientes de aceptación se consultan en el plan del CRUD. [cite: 1]

El frontend utiliza React 19, TypeScript y Mantine, pero todavía no tiene una biblioteca cartográfica instalada.

Las especificaciones actuales de F-01 excluyen los mapas; se actualizará ese alcance antes de implementar. [cite: 5]

## 4. Datos iniciales preparados

### 4.1. Archivo de origen

La preparación local utilizó la versión detallada del checkout de `geojson/peru-geojson-datasets/`, fuera de este repositorio:

```text
/home/elesel/Escritorio/geojson/peru-geojson-datasets/lima_callao_distritos.geojson
```

El origen contiene 50 entidades: 43 distritos de Lima y siete del Callao.

| Archivo | Contenido observado | Tamaño aproximado |
|---|---|---|
| `lima_callao_distritos.geojson` | 50 entidades `MultiPolygon`; utilizado para exportar las geometrías completas. | 1,65 MiB. |
| `lima_callao_distritos_simple.geojson` | 50 entidades `Polygon`; referencia disponible, no utilizada en la exportación. | 141 KiB. |

El README del origen declara licencias MIT y Apache-2.0 y referencia las fuentes geográficas del conjunto. Queda pendiente fijar su revisión y documentar la procedencia, atribución y vigencia de los límites antes de adoptar el conjunto para la carga inicial. [cite: 6]

### 4.2. Entregable local: un GeoJSON por distrito

Se generaron 50 archivos UTF-8 con JSON legible en:

```text
/home/elesel/Escritorio/distritos-geojson/
├── 070101_callao.geojson
├── 070107_mi_peru.geojson
├── 150101_lima.geojson
├── 150130_san_borja.geojson
└── ... (50 archivos en total)
```

Cada archivo contiene un `FeatureCollection` con una única `Feature` y la geometría `MultiPolygon` completa del distrito. Sus propiedades son exclusivamente:

| Propiedad | Contenido |
|---|---|
| `nombre` | Nombre legible de la tabla proporcionada por el usuario. |
| `provincia` | `Lima` o `Callao`. |
| `departamento` | `Lima` o `Callao`, respectivamente. |
| `ubigeo` | Texto de seis dígitos, preservando los ceros iniciales del Callao. |

Los nombres de archivo siguen `<ubigeo>_<nombre_sin_tildes_en_minusculas_con_guiones_bajos>.geojson`. Ejemplos: `150130_san_borja.geojson` y `070107_mi_peru.geojson`.

La correspondencia se resolvió por provincia y nombre normalizados, utilizando la tabla de 50 ubigeos proporcionada por el usuario. Se corrigió el nombre mal codificado de Mi Perú en la exportación y se le asignó `"070107"`.

Los identificadores numéricos del origen no son ubigeos ni UUID del catálogo. Los archivos exportados no inventan UUID, estado, versión ni fechas administrativas. La importación resolverá el distrito existente por `ubigeo`, conservando su identidad y los datos del catálogo.

### 4.3. Comprobaciones realizadas

- 50 archivos releídos y deserializados correctamente, con una entidad por archivo.
- Distribución de 43 distritos de Lima y siete del Callao.
- 50 ubigeos de texto válidos por formato y únicos, coincidentes con la tabla del usuario, sin correspondencias faltantes o repetidas.
- Las cuatro propiedades previstas y los nombres de archivo coinciden con cada distrito.
- Igualdad profunda de las 50 geometrías respecto del origen, incluidas sus 72 489 posiciones de coordenadas numéricas finitas.
- Archivo de origen intacto, comprobado mediante SHA-256 antes y después de exportar.

Esta comprobación acredita la estructura, la correspondencia con la tabla y la conservación de las geometrías. La validez topológica mediante PostGIS, la vigencia de los límites y la correspondencia contra el catálogo del entorno de importación siguen pendientes.

### 4.4. Preparación pendiente para la entrega

1. Fijar procedencia y revisión del conjunto, con su atribución y licencias.
2. Validar con PostGIS las geometrías y revisar su calidad geográfica en una base desechable.
3. Contrastar los ubigeos contra el catálogo del entorno de prueba, sin modificar sus nombres, estados ni asociaciones.
4. Incorporar el proceso de exportación y verificación como una herramienta mantenida, con catálogo de correspondencias, huellas y resultado reproducible.
5. Definir la ubicación compartida y versionada del conjunto aceptado y de su manifiesto de procedencia.

La carpeta del Escritorio y el script temporal de exportación son artefactos locales de preparación; la aplicación recibirá el contenido mediante el importador, sin depender de esas rutas. Los archivos originales se conservan.

## 5. Persistencia y geometrías

### 5.1. Geometría distrital

`distrito.geom` contendrá la geometría vigente del distrito:

- `Polygon` o `MultiPolygon`.
- Coordenadas WGS84, SRID `4326`.
- Orden GeoJSON: longitud, latitud.
- Conservación de partes separadas y huecos.

Se incorporarán los metadatos necesarios:

- Versión de geometría.
- Fuente y versión del conjunto.
- Fecha de importación.
- Usuario ejecutor.
- Huella del contenido.
- Referencia a la importación y al histórico.

La geometría seguirá siendo opcional. Un distrito nuevo podrá existir y utilizarse sin tener todavía un límite cargado.

Se conservará la geometría completa aceptada. Si hace falta una versión simplificada para el mapa, será una representación derivada que preserve su identidad y calidad visual, sin sustituir el dato original.

### 5.2. Histórico

Cada reemplazo conservará la versión anterior y su procedencia.

La geometría vigente, su nueva versión y su auditoría se confirmarán en la misma transacción. Los metadatos de importación permitirán identificar qué archivo y qué usuario produjeron cada cambio.

La identidad del ejecutor procederá del JWT y la fecha, del servidor. El histórico será inmutable desde las operaciones de esta entrega.

### 5.3. Representación de zonas

Las zonas configuradas por distritos se representarán a partir de las geometrías de sus asociaciones.

En esta entrega, esa representación será derivada, evitando mantener una forma editable independiente que contradiga los distritos seleccionados.

Se distinguirán:

- Cobertura distrital completa.
- Distritos asociados inactivos.
- Zona inactiva.
- Cobertura exclusivamente postal.
- Distritos sin geometría disponible.

La cobertura exclusivamente postal no se pintará como cobertura de todo el distrito. El límite distrital podrá mostrarse como referencia, identificado expresamente.

La columna `zonas.geom` se evaluará para la futura etapa de delimitaciones dibujadas; su existencia no activará ese comportamiento.

## 6. Importación desde la pantalla

En la sección Distritos habrá una acción Importar geometrías.

### 6.1. Flujo completo

1. Seleccionar un archivo GeoJSON.
2. Mostrar su estructura y cantidad de entidades.
3. Identificar las correspondencias con distritos.
4. Resolver asociaciones faltantes.
5. Validar la propuesta en el backend.
6. Mostrar informe y previsualización.
7. Confirmar el reemplazo.
8. Guardar geometrías, histórico y auditoría.
9. Actualizar la vista administrativa.

El importador aceptará los archivos individuales de la sección 4: por ejemplo, cargar `150130_san_borja.geojson` propondrá aplicar una geometría al distrito con ubigeo `150130`. No será necesario reunir los 50 distritos en un único archivo. Un `FeatureCollection` con varias entidades seguirá el mismo flujo, sujeto a los límites de la sección 8.1; cada archivo confirmado constituye su propio lote atómico.

### 6.2. Identificación de distritos

- Si existe ubigeo válido en `properties.ubigeo`, se asociará con el distrito correspondiente, incluidos los ceros iniciales del Callao; el backend confirmará su existencia.
- Si falta, se podrán sugerir coincidencias por departamento, provincia y nombre.
- Una sugerencia nominal requerirá confirmación explícita.
- Los distritos desconocidos deberán registrarse mediante el CRUD.
- Los nombres y estados del archivo no sobrescribirán los del catálogo.

Para los 50 archivos preparados, los nombres, la provincia y el departamento sirven como información de revisión; el ubigeo determina la correspondencia. La fuente y la versión se declararán en la importación y se conservarán como metadatos, ya que no forman parte de esas cuatro propiedades.

### 6.3. Previsualización

Mostrará:

- Límites actuales y propuestos.
- Distritos que recibirán su primera geometría.
- Distritos que tendrán un reemplazo.
- Correspondencias por ubigeo.
- Fuente y versión declaradas.
- Errores y observaciones.
- Resumen de entidades que se guardarán.

Los distritos omitidos en el archivo conservarán sus geometrías.

## 7. Validaciones y control de importación

El backend validará tanto la previsualización como la confirmación.

### 7.1. Validaciones

- JSON y estructura GeoJSON válidos.
- Tipos permitidos: `Polygon` y `MultiPolygon`.
- Coordenadas numéricas finitas.
- Anillos cerrados y geometrías no vacías.
- Validez geométrica mediante PostGIS.
- Ubigeos existentes y sin duplicados.
- Correspondencia geográfica plausible con Lima y Callao.
- Tamaño y complejidad dentro de los límites.

Los archivos en coordenadas proyectadas, como UTM, necesitarán una conversión con sistema de origen conocido. No se les asignará `4326` sin transformar sus coordenadas.

Las geometrías inválidas se rechazarán con explicación. No se aplicarán reparaciones automáticas que cambien límites sin revisión.

### 7.2. Consistencia

- La confirmación comprobará las versiones vigentes.
- Un cambio concurrente exigirá revisar nuevamente la propuesta.
- El lote será atómico: se guarda completo o se revierte.
- La importación será idempotente para recuperar una respuesta perdida.
- La importación solo modificará geometrías y sus metadatos.

Se distinguirán errores de formato, correspondencia, geometría, versión y persistencia. Los errores seguirán las convenciones compartidas y no presentarán una importación rechazada como aceptada. [cite: 2, 3]

## 8. APIs administrativas propuestas

Se formalizarán en el contrato de Gestión antes de implementar sus consumidores. Esta tabla describe la propuesta de trabajo, no sustituye su definición canónica. [cite: 2]

| Método | Ruta propuesta | Función |
|---|---|---|
| `GET` | `/api/v1/distritos/geometrias` | Consultar la capa distrital. |
| `GET` | `/api/v1/zonas/{id}/geometria` | Consultar la representación de una zona. |
| `POST` | `/api/v1/distritos/geometrias/validaciones` | Validar una propuesta sin aplicar cambios. |
| `POST` | `/api/v1/distritos/geometrias/importaciones` | Confirmar una importación. |

Todas requerirán JWT humano con rol `GESTOR_DESPACHO` y se consumirán mediante el Gateway. Los tokens técnicos de Marketplace y Chatbot no sustituirán ese permiso.

Las geometrías se consultarán separadamente del listado habitual de distritos para evitar respuestas pesadas en los formularios existentes.

### 8.1. Gateway y tamaños

El Gateway tiene actualmente un límite general de 1 MiB. Se añadirá una configuración específica para validación e importación geográfica. [cite: 7]

Valores iniciales propuestos:

- Máximo de 5 MiB por solicitud.
- Hasta 100 entidades.
- Hasta 200 000 posiciones de coordenadas.

Se verificarán en Gateway y Gestión. El límite habitual de las solicitudes de cotización conservará su configuración.

Los 50 archivos preparados ocupan en total 6 382 371 bytes (aproximadamente 6,09 MiB) por su formato legible. El mayor es `150114_la_molina.geojson`, con 880 224 bytes (aproximadamente 0,84 MiB); todos caben individualmente bajo el máximo propuesto de 5 MiB. Las 72 489 posiciones del conjunto completo también quedan por debajo del límite de complejidad propuesto. El tamaño se comprobará sobre el cuerpo real enviado, incluidos los metadatos y su envoltura, no sobre la suma de tamaños de una carpeta.

## 9. Frontend administrativo

### 9.1. Maquetas disponibles

Se prepararon seis maquetas de referencia en `/home/elesel/Escritorio/maquetas-f01-geometrias-mapas/`, con `galeria.html`, `LEEME.md` y `00-vista-general.png` para su revisión conjunta:

| Archivo | Escenario |
|---|---|
| `01-distritos-mapa.png` | Mapa de distritos, filtros, leyenda y ficha geográfica. |
| `02-zona-seleccion-mapa.png` | Formulario de zona y selección sincronizada con el mapa. |
| `03-importar-archivo.png` | Selección del GeoJSON y declaración de fuente y versión. |
| `04-correspondencias-distritos.png` | Coincidencias por ubigeo y revisión de asociaciones. |
| `05-previsualizar-confirmar.png` | Comparación y confirmación de primeras cargas y reemplazos. |
| `06-validacion-errores.png` | Incidencias geométricas y de correspondencia. |

Son propuestas visuales con datos y estados ilustrativos, no evidencia de una interfaz implementada ni de errores reales del conjunto. Se trasladarán al diseño de Specs al formalizar el alcance. [cite: 10]

### 9.2. Sección Distritos

Mantendrá su acceso independiente de Zonas y tarifas.

Incluirá:

- Vista Tabla / Mapa.
- Búsqueda y filtros de estado.
- Acción Importar geometrías.
- Identificación de distritos sin geometría.
- Panel con nombre, ubigeo, estado, fuente y versión.
- Acción para centrar el distrito seleccionado.
- Leyenda accesible de estados y selección.

La leyenda combinará color, contorno y texto, respetando el tema existente.

### 9.3. Formulario de zonas

En escritorio se dispondrán el formulario y el mapa en paneles contiguos. En móvil se ofrecerán vistas adaptadas y un resumen claro de selección.

Comportamiento:

- Seleccionar en el mapa actualizará el selector.
- Seleccionar en la lista resaltará el mapa.
- Ambos utilizarán un único estado del formulario.
- Se conservarán códigos postales y el indicador de cobertura exclusivamente postal.
- Los distritos inactivos ya asociados seguirán visibles.
- Los distritos inactivos no se podrán añadir como nuevas asociaciones.
- Los conflictos se presentarán visualmente y se validarán en el backend.
- La selección se persistirá únicamente al pulsar Guardar zona.

### 9.4. Tecnología y funcionamiento

Propuesta técnica:

- Leaflet 1.9.x.
- React Leaflet 5, compatible con React 19.
- Tipos TypeScript de GeoJSON y Leaflet.
- Componentes y estilos integrados con Mantine.

La compatibilidad de React Leaflet 5.0.0 con React 19 y Leaflet 1.9.x está declarada en sus dependencias publicadas; la incorporación y la resolución concreta se verificarán en el lockfile del frontend. [cite: 8]

El mapa se cargará cuando se necesite. El proveedor de mapa base será configurable y tendrá atribución visible.

Si fallan las imágenes del mapa base o la consulta geográfica, el usuario conservará el formulario y podrá trabajar mediante la lista.

## 10. Especificaciones y documentación

En Specs se prepararán comportamientos atómicos para:

1. Consultar la representación geográfica.
2. Validar una importación.
3. Importar o reemplazar geometrías.
4. Seleccionar distritos desde el mapa.

Se actualizarán:

- F-01 y su diseño visual.
- El contrato administrativo de Gestión.
- El modelo de datos.
- La especificación del formulario de zonas.
- La trazabilidad de configuración.
- El stack frontend.
- Las instrucciones de configuración y ejecución que cambien.

El contrato externo de cotización conservará sus operaciones, solicitudes y respuestas.

Las migraciones utilizarán las siguientes versiones disponibles al implementar, coordinadas con las del CRUD de distritos. No se reservará nuevamente V11 ni se editarán migraciones ya aplicadas.

Las carpetas SDD incluirán `spec.md`, `plan.md`, `tasks.md`, `ui.md` cuando corresponda y `evidence.md`. La evidencia distinguirá APIs simuladas, pruebas automatizadas, integración real y aceptación en navegador. [cite: 9]

## 11. Verificación y criterios de aceptación

### 11.1. Backend y datos

- Importación válida y consulta de GeoJSON.
- Carga de un archivo distrital preparado, incluyendo Mi Perú con `"070107"`, y comprobación de asociación al UUID existente por ubigeo.
- Conservación de partes separadas y huecos.
- Rechazo de geometrías inválidas y ubigeos desconocidos.
- Límites de tamaño y complejidad.
- Conflictos concurrentes e idempotencia.
- Reversión completa ante fallos.
- Histórico y auditoría coherentes.
- Conservación de nombres, estados, tarifas y asociaciones.

La conservación de partes separadas y huecos se probará además con geometrías de prueba específicas: las 50 entidades del conjunto preparado contienen en total 50 partes y 50 anillos, por lo que no ejercitan esos casos por sí solas.

### 11.2. Frontend

- Visualización de los distritos con geometría.
- Identificación de datos faltantes.
- Sincronización de mapa y selector.
- Conservación de códigos postales y cobertura postal exclusiva.
- Previsualización antes del reemplazo.
- Recuperación ante errores sin perder el formulario.
- Accesibilidad por teclado y adaptación móvil.

### 11.3. Regresión obligatoria de Marketplace y Chatbot

Con productos, tarifa y reloj controlados:

1. Cotizar un distrito mediante el contrato actual.
2. Importar su geometría.
3. Repetir la misma solicitud.
4. Comprobar igualdad de cobertura, costo, moneda, peso, volumen y plazo.
5. Reemplazar la geometría y repetir la comprobación.
6. Verificar que un distrito sin geometría también puede cotizar.
7. Comprobar que las respuestas no incorporan campos geográficos nuevos.

La baja y reactivación del distrito se verificarán separadamente conforme al CRUD.

### 11.4. Comandos

Desde `servicio-gestion-despachos/` y desde `api-gateway/`, respectivamente:

```bash
./mvnw clean verify
```

Las pruebas de integración de Gestión utilizarán bases desechables de Testcontainers.

Desde la raíz de G2-Despacho-Frontend:

```bash
npm run test:f01
npm run test:administrativo
npm run typecheck
npm run build
```

Se ajustará el alcance de las suites si las nuevas carpetas quedan fuera de su selección actual. Se ejecutarán las pruebas de componentes compartidos si se modifican esos componentes.

El recorrido en navegador se realizará mediante el Gateway y un entorno de prueba aislado. Las pruebas E2E actuales de F-01 escriben datos reales y no aíslan automáticamente su base; se comprobará el entorno antes de ejecutarlas.

## 12. Orden de implementación

| Etapa | Entregable | Estado al 14/11/2026 |
|---|---|---|
| 1 | Especificaciones, contrato administrativo y diseño de interfaz. | Pendiente en Specs; seis maquetas locales disponibles como insumo. |
| 2 | Dataset normalizado y correspondencias por ubigeo revisadas. | Exportación de 50 archivos y comprobaciones estructurales completadas; falta aceptación geográfica, contraste con el catálogo y preparación reproducible de entrega. |
| 3 | Persistencia, versiones, histórico y auditoría. | Pendiente; utilizar una migración nueva posterior a las existentes. |
| 4 | APIs de consulta, validación e importación; ajuste acotado del Gateway. | Pendiente. |
| 5 | Mapa de distritos e importador visual. | Pendiente; aceptar los GeoJSON individuales preparados. |
| 6 | Selección en mapa y representación de zonas. | Pendiente. |
| 7 | Pruebas de regresión, recorrido integrado y evidencia. | Pendiente. |

El siguiente incremento es formalizar el alcance y las operaciones en Specs utilizando las maquetas y los archivos ya preparados. La preparación restante del conjunto puede avanzar en paralelo; la persistencia y las APIs se implementarán después de fijar esas definiciones.

Las ramas de código partirán de `develop`, por ejemplo `feature/f01-geometrias-mapas`. La documentación canónica de Specs seguirá su flujo de ramas desde `main`. [cite: 9]

### 12.1. Evolución posterior

El dibujo de límites tendrá su propio alcance: edición de vértices, validaciones de solapamiento y distinción entre límites administrativos y polígonos de cobertura. Cualquier futura resolución por coordenadas requerirá una decisión contractual adicional.

El resultado esperado de esta entrega es que el administrador pueda visualizar territorios, seleccionar distritos e importar sus límites, mientras Marketplace y Chatbot siguen utilizando la misma integración de cotización que funciona actualmente.

## 13. Estado de verificación

Al 14/11/2026 están disponibles los 50 GeoJSON distritales y las seis maquetas locales. Se ejecutaron las comprobaciones de exportación descritas en la sección 4.3, con 50 correspondencias correctas y conservación íntegra de las geometrías.

La huella SHA-256 del archivo detallado utilizado es:

```text
b0627d6c0a5617fef2054ef066f54dd2d1e4c15d2506c7fef034fef6fc48db9e
```

El script utilizado para generar y verificar los archivos quedó temporalmente en `/tmp/opencode/exportar-distritos-geojson.mjs`; su incorporación a una ubicación mantenida sigue pendiente.

Las APIs geográficas, la persistencia de sus metadatos e histórico y los mapas siguen pendientes de implementación. Esta preparación no ejecutó migraciones, no importó geometrías a una base ni validó su topología con PostGIS. Las pruebas funcionales y de integración de la sección 11, incluidos Maven, frontend y recorrido por Gateway, siguen pendientes para esta ampliación. La evidencia previa del CRUD de distritos mantiene su alcance propio. [cite: 1]

## 14. Referencias

1. [cite: 1] [Plan de administración de distritos](./plan-f01-administracion-distritos.md).
2. [cite: 2] [Contrato de Gestión de Despachos](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract-gestion-despachos.md).
3. [cite: 3] [Contrato externo y convenciones compartidas](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/integraciones/api-contract.md), especialmente cotización, autenticación, errores e idempotencia.
4. [cite: 4] [Migración V1: esquema e índices espaciales](../src/main/resources/db/migration/V1__esquema_original_gestion.sql) y [dependencias de Gestión](../pom.xml).
5. [cite: 5] [Funcionalidad F-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/funcionalidades/F-01-Gestor_ZonasGeograficas.md), [diseño visual de F-01](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/disenio/funcionalidades/f-01.md) y [UI de ES-F01-02](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-02-RegistrarZonaCobertura/ui.md).
6. [cite: 6] [Repositorio peru-geojson-datasets](https://github.com/joseluisq/peru-geojson-datasets), su README y licencias `LICENSE-MIT` y `LICENSE-APACHE`; origen de la exportación local, con revisión y adecuación geográfica pendientes según la sección 4.
7. [cite: 7] [Configuración del Gateway](../../api-gateway/src/main/resources/application.yaml) y [filtro de tamaño de solicitudes](../../api-gateway/src/main/java/com/modulodespacho/api_gateway/transversal/FiltroTamanoSolicitud.java).
8. [cite: 8] [Metadatos publicados de React Leaflet 5.0.0](https://registry.npmjs.org/react-leaflet/5.0.0), [documentación de React Leaflet](https://react-leaflet.js.org/docs/start-introduction/) y [política de teselas de OpenStreetMap](https://operations.osmfoundation.org/policies/tiles/) si se utiliza ese proveedor.
9. [cite: 9] [Reglas del backend](../../AGENTS.md), [reglas de Specs](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/AGENTS.md) y [guía de contribución](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/CONTRIBUTING.md).
10. [cite: 10] Galería local `/home/elesel/Escritorio/maquetas-f01-geometrias-mapas/galeria.html` y guía `LEEME.md` de esa carpeta; propuestas visuales pendientes de incorporación a Specs.
