# Plan: administración unificada de distritos

**Fecha:** 13/11/2026.

**Estado:** Implementado y verificado localmente el 13/11/2026; revisión por pares y aceptación humana pendientes.

**Ámbito:** Frontend administrativo y documentación de F-01.

**Solución acordada:** Un botón **Distritos** en Zonas y tarifas que abre una **pantalla completa con dos columnas**: formulario a la izquierda y listado con todas las acciones a la derecha.

## 1. Resultado visual esperado

En **Zonas y tarifas**:

```text
[Actualizar]  [Nueva zona]  [Distritos]
```

Al pulsar **Distritos**:

```text
DISTRITOS                          [Actualizar] [Volver a zonas]

┌──────────────────────────┬──────────────────────────────────────┐
│ NUEVO DISTRITO           │ DISTRITOS REGISTRADOS                │
│                          │                                      │
│ Nombre                   │ Buscar: [____________]               │
│ [____________________]   │ Estado: [Activos / Inactivos / Todos] │
│                          │                                      │
│ Provincia                │ Distrito    Ubigeo  Estado  Acciones │
│ [____________________]   │                                      │
│                          │ San Borja   150130  Activo  Editar   │
│ Departamento             │                            Desactivar│
│ [____________________]   │                                      │
│                          │ Miraflores  150122  Inactivo Editar  │
│ Ubigeo                   │                             Reactivar│
│ [____________________]   │                                      │
│                          │                                      │
│ [Guardar distrito]       │                                      │
│ [Limpiar formulario]     │                                      │
└──────────────────────────┴──────────────────────────────────────┘
```

El listado también mostrará provincia y departamento. Se dará suficiente ancho a la tabla para que las acciones sean legibles.

El menú lateral mantendrá **Zonas y tarifas** como sección activa durante todo este recorrido.

## 2. Base existente que se reutilizará

Ya están implementados:

- Listado, búsqueda y filtro de distritos.
- Creación y edición con validación.
- Protección de nombre y ubigeo.
- Desactivación y reactivación con confirmación.
- Control de versión y tratamiento de errores.
- APIs y tipos del cliente.
- Componentes compartidos, Mantine, React Hook Form y Zod.

El cambio principal será **coordinar esas funciones en una misma pantalla**. Actualmente el formulario y el listado navegan por separado; esa separación se sustituirá por selección y edición dentro del panel.

## 3. Navegación y conservación del contexto

### 3.1. Acceso desde zonas

- Renombrar **Nuevo distrito** a **Distritos**.
- Abrir la administración en `/distritos`.
- Conservar la consulta original de zonas: nombre, distrito, estado y página.
- Mantener ese contexto separado de los filtros del catálogo distrital, para evitar que el nombre de una zona se interprete como búsqueda de un distrito.

### 3.2. Rutas existentes

| Ruta | Comportamiento previsto |
|---|---|
| `/distritos` | Pantalla completa, formulario de alta y listado. |
| `/distritos/nuevo` | Acceso compatible a la misma administración en modo alta. |
| `/distritos/:id/editar` | Misma pantalla, con el distrito cargado en el formulario lateral. |

### 3.3. Regreso

**Volver a zonas** restaurará la consulta original. Si se entra directamente a Distritos, el regreso será al listado general de zonas.

Guardar una creación o edición mantendrá abierta la administración de distritos.

## 4. Formulario de creación y edición

### 4.1. Modo Nuevo distrito

Campos:

- Nombre.
- Provincia.
- Departamento.
- Ubigeo como texto, conservando ceros iniciales.

Acciones:

- **Guardar distrito**.
- **Limpiar formulario**.

Después de una creación confirmada:

1. Mostrar notificación de éxito.
2. Limpiar el formulario.
3. Mantener abierta la pantalla.
4. Consultar nuevamente el listado.
5. Conservar los filtros actuales.

Si el nuevo registro queda oculto por los filtros —por ejemplo, estando en Inactivos— se informará de esa situación.

### 4.2. Modo Editar distrito

Al pulsar **Editar**:

1. Consultar el detalle vigente.
2. Cargar sus datos y `versionDistrito`.
3. Cambiar el título a **Editar distrito**.
4. Mostrar **Guardar cambios** y **Cancelar edición**.
5. Identificar visualmente qué registro se está editando.

Se conservarán las restricciones actuales:

- Nombre y ubigeo protegidos para el catálogo base.
- Protección de identidad cuando existan asociaciones.
- Explicación visible del motivo.
- Validación geográfica y de unicidad.

Al guardar correctamente o cancelar la edición, el panel izquierdo volverá al modo de alta.

## 5. Listado y cambios de estado

El panel derecho incluirá:

- Búsqueda por nombre.
- Filtro **Activos / Inactivos / Todos**, con Activos por defecto.
- Nombre, provincia, departamento, ubigeo y estado.
- **Editar** en cada registro.
- **Desactivar** para activos.
- **Reactivar** para inactivos.

### 5.1. Confirmaciones

La desactivación explicará que el distrito dejará de admitir nuevas cotizaciones y solicitudes, conservando asociaciones y despachos existentes.

La reactivación explicará que la cobertura dependerá de que su zona y configuración continúen vigentes.

Después de una operación aceptada:

- Refrescar el listado conservando filtros.
- Mostrar el estado confirmado por el servidor.
- Respetar que una fila pueda desaparecer del filtro actual.

Si el cambio afecta al distrito cargado en el formulario, se coordinará su salida de edición. El formulario de otro distrito conservará sus valores.

## 6. Protección de cambios y manejo de errores

### 6.1. Cambios sin guardar

Se pedirá confirmar el descarte al:

- Seleccionar otro distrito para editar.
- Cancelar una edición modificada.
- Limpiar un formulario con datos.
- Volver a zonas.

Cancelar esa confirmación conservará el formulario. También se incorporará el aviso de cierre o recarga con cambios pendientes, siguiendo el patrón existente del proyecto.

### 6.2. Concurrencia

- Enviar siempre la versión obtenida del detalle.
- Un conflicto conservará lo escrito y ofrecerá consultar los datos actuales.
- La consulta posterior requerirá revisar y descartar expresamente el borrador.
- La interfaz no reenviará automáticamente una decisión sobre otra versión.
- Durante una escritura se bloquearán los envíos y acciones incompatibles.

**Actualizar** renovará el listado sin reemplazar silenciosamente los datos de una edición abierta.

### 6.3. Errores y permisos

- Validaciones y duplicados: mensajes por campo, conservando valores.
- Error del listado: recuperación dentro de su panel, sin borrar el formulario.
- Detalle inexistente: mensaje explícito, sin presentar una edición vacía.
- `401/403`: retirar formulario, filas y confirmaciones.
- Respuestas atrasadas: ignorarlas cuando correspondan a otra selección o consulta.

Un guardado confirmado seguirá mostrándose como exitoso aunque falle el refresco posterior. En ese caso se ofrecerá repetir únicamente la consulta.

## 7. Organización del código

Las rutas siguientes pertenecen a `G2-Despacho-Frontend/apps/despacho-administrativo/src/`:

| Archivo | Trabajo previsto |
|---|---|
| `features/zonas/PaginaZonas.tsx` | Botón Distritos y contexto de regreso. |
| `features/distritos/PaginaDistritos.tsx` | Composición de ambos paneles y coordinación de selección, consultas, mutaciones y confirmaciones. |
| `features/distritos/FormularioDistrito.tsx` | Formulario reutilizable dentro del panel, con datos y callbacks, sin navegación propia ni otro título principal. |
| `features/distritos/ListadoDistritos.tsx` | Extraer tabla, filtros y acciones de presentación. |
| `features/distritos/distritosApi.ts` | Reutilizar las operaciones actuales. |
| `Aplicacion.tsx` | Integrar las rutas con la misma pantalla y conservar el destino lateral activo. |

`PaginaDistritos` será el punto de coordinación de la pantalla, evitando consultas duplicadas o estados independientes que puedan sobrescribirse.

Las APIs actuales de Gestión respaldan el flujo. Esta reorganización se concentra en frontend y documentación.

## 8. Adaptación y accesibilidad

- Dos columnas en escritorio amplio; formulario y listado apilados en pantallas menores.
- Un solo título principal de página y títulos de sección para cada panel.
- Desplazamiento horizontal de la tabla contenido dentro de su región.
- Etiquetas y errores asociados a los campos.
- Al seleccionar Editar, desplazar y llevar el foco al formulario, especialmente en móvil.
- Confirmaciones con foco controlado y retorno al elemento correspondiente.
- Estados expresados con texto, además de color.
- Reutilización del tema y componentes compartidos existentes.

## 9. Orden de implementación

| Incremento | Entregable verificable |
|---|---|
| 1. Especificaciones | Actualizar UI, planes y tareas de ES-F01-09 a ES-F01-12 con el panel unificado. |
| 2. Acceso y rutas | Botón Distritos, rutas compatibles y regreso a zonas con contexto conservado. |
| 3. Composición | Formulario y listado visibles simultáneamente, con adaptación móvil. |
| 4. Operaciones | Crear, editar, limpiar, cancelar, desactivar y reactivar dentro de la pantalla. |
| 5. Consistencia | Protección de borradores, conflictos, errores, permisos y refrescos coordinados. |
| 6. Verificación | Suites, recorrido en navegador y evidencia de la nueva organización. |

Se continuará sobre `feature/f01-administracion-distritos`, preservando el trabajo local existente. La documentación canónica se mantendrá en Specs, con los identificadores actuales.

## 10. Pruebas y criterios de aceptación

### 10.1. Pruebas automatizadas

Actualizar y ampliar:

- `Distritos.test.tsx`.
- `NavegacionDistritos.test.tsx`.
- `Zonas.test.tsx`.
- `pruebas/distritos/administracion.spec.ts`.

Escenarios principales:

1. Entrar desde Zonas y tarifas y encontrar formulario y listado.
2. Crear sin abandonar la administración.
3. Editar una fila en el formulario lateral.
4. Cancelar edición y volver al modo de alta.
5. Confirmar o cancelar el descarte de cambios.
6. Conservar filtros durante las mutaciones.
7. Desactivar y reactivar con confirmación.
8. Manejar identidad protegida y versión obsoleta.
9. Conservar un guardado exitoso ante fallo del refresco.
10. Regresar a zonas con su consulta original y catálogo actualizado.
11. Verificar escritorio, móvil y navegación por teclado.

### 10.2. Comandos previstos

Desde la raíz del frontend:

```bash
npm run test:f01
npm run test:administrativo
npm run typecheck
npm run build
npm run test:distritos:e2e
git diff --check
```

El recorrido de navegador utilizará el entorno desechable ya preparado para F-01. La evidencia distinguirá componentes con APIs simuladas, integración local por Gateway y aceptación humana.

### 10.3. Condición de cierre

La implementación estará lista cuando **crear, consultar, editar, desactivar y reactivar sean accesibles desde el botón Distritos**, el formulario y el listado trabajen coordinadamente y las comprobaciones correspondientes estén aprobadas.

La ejecución local se registra a continuación; la evidencia canónica está en Specs.

## 11. Ejecución local — 13/11/2026

- Especificaciones ES-F01-09 a ES-F01-12 actualizadas: UI, planes, T-06 y evidencia, conservando los identificadores existentes.
- Botón Distritos y tres rutas compatibles a la misma pantalla; regreso con contexto de zonas separado de los filtros distritales.
- Formulario/listado coordinados, grilla responsive, edición con foco y marca de selección, alta/edición sin salir y cambios de estado confirmados.
- Descarte cancelable y aviso de cierre/recarga; versión del detalle, borradores preservados ante conflictos y errores, bloqueo de escrituras, retirada completa ante `401/403` y respuestas atrasadas ignoradas.
- `npm run test:f01`: 52 aprobadas; `npm run test:administrativo`: 262 aprobadas; `npm run typecheck` y `npm run build`: aprobados. Vite conserva avisos de tamaño de chunks.
- `npm run test:distritos:e2e`: dos recorridos Chromium aprobados con Gateway/Gestión reales locales, PostgreSQL desechable, Seguridad temporal y Productos simulados; escritorio, móvil, teclado y capturas inspeccionadas. Se reutilizaron JAR locales ya construidos; Maven no se volvió a ejecutar en esta reorganización de frontend.

Detalles, capturas y límites en [ES-F01-12, administración unificada](https://github.com/Taller-SW-Web/G2-Despacho-Specs/blob/main/especificaciones/ES-F01-12-ActivarDesactivarDistrito/evidence.md#3-administración-unificada-de-distritos). La revisión por pares, aceptación humana e integración publicada siguen pendientes.
