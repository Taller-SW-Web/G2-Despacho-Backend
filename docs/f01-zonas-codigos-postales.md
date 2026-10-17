# F-01: zonas con códigos postales agrupados por distrito

## Crear o editar una zona

`POST /api/v1/zonas` y `PUT /api/v1/zonas/{idZona}` requieren un token
de usuario con rol `GESTOR_DESPACHO`. El formato de `distritos` ahora es una
lista de objetos, con los códigos postales dentro de cada distrito:

```json
{
  "nombre": "Zona Norte",
  "estado": "ACTIVO",
  "distritos": [
    {
      "distrito": "Comas",
      "codigosPostales": [
        "15311", "15312", "15313", "15314", "15316",
        "15324", "15326", "15327", "15328", "15332"
      ]
    },
    {
      "distrito": "Ancón",
      "codigosPostales": []
    }
  ]
}
```

En el ejemplo Ancón todavía no tiene CP registrados. Para registrarlos,
reemplazar su lista vacía por los códigos deseados como strings.

- Se valida que cada distrito exista en el catálogo, sin distinguir mayúsculas
  y recortando espacios. Usar los nombres de `GET /api/v1/distritos`.
- Por ahora **no se valida la pertenencia ni la existencia del CP** en un padrón
  postal. Los códigos proporcionados se asocian al distrito indicado.
- `codigosPostales` es opcional; los espacios se recortan y los repetidos de
  una misma lista se guardan una sola vez.
- Se requiere al menos un distrito. No se puede repetir el mismo distrito en
  una solicitud.
- Se mantiene la cobertura por **distrito o CP**: registrar un distrito cubre
  todo ese distrito; la lista de CP no limita la cobertura a esos códigos.
  Si no hay coincidencia por distrito, la resolución también busca por CP.
- Se rechazan distritos o CP ya asignados a otra zona activa con `409`.
- El `PUT` reemplaza todos los distritos y CP de la zona; los omitidos se quitan.
  El estado se cambia con `PATCH /api/v1/zonas/{idZona}/estado`.

## Respuesta y compatibilidad

Las respuestas de creación, edición, consulta, listado y cambio de estado
incluyen `distritos` con esta forma (fragmento):

```json
{
  "distritos": [
    {
      "id": "uuid-del-distrito",
      "nombre": "Comas",
      "codigosPostales": ["15311", "15312"]
    }
  ]
}
```

Los distritos se ordenan por nombre y los CP por código. **El contrato cambió**:
ya no se reciben nombres sueltos en `distritos` ni se devuelve la lista plana
`codigosPostales` de primer nivel. El catálogo `GET /api/v1/distritos?nombre=comas`
mantiene su formato (`id`, `nombre`, `provincia`, `departamento`, `ubigeo`).

## Persistencia y padrón

- `zonas_distritos`: una fila por distrito de la zona.
- `zonas_codigos_postales`: una fila por zona, distrito y CP. La aplicación usa
  la tabla ya creada en Supabase. No se altera ninguna tabla existente.
- `V3__zonas_codigos_postales.sql` reproduce esa tabla para bases nuevas y las
  pruebas con Testcontainers. No volver a aplicarla en Supabase si ya existe.
- `V4__seed_distritos_lima_callao.sql` carga 43 distritos de Lima provincia y 7
  del Callao, usando códigos INEI contrastados con el catálogo de CONCYTEC
  enlazado en el SQL. `ON CONFLICT (ubigeo) DO NOTHING` conserva los registros
  existentes, incluidos sus UUID y nombres (por ejemplo, `Surco` en Supabase).
- En este entorno Supabase mantiene Flyway deshabilitado: se aplicó únicamente
  el seed V4, que agregó 47 distritos a los 3 existentes (50 en total).

## Errores de persistencia

Los duplicados de base de datos (`SQLSTATE 23505`) responden `409` con
`DESP_ERROR_CONFLICTO_PERSISTENCIA`. Otras violaciones de integridad responden
`500` con `DESP_ERROR_INTERNO`, sin exponer SQL al cliente. Los detalles se
registran en el servidor. La seguridad permite el despacho interno de errores
(`DispatcherType.ERROR`) para que un fallo no termine convertido en un `403`.

## Verificación breve

Desde `servicio-gestion-despachos`, con Docker disponible:

```bash
./mvnw -q -Dtest=FlujoF01Tests test
```

La clase contiene 7 pruebas: flujo zona/tarifa/cotización, conflictos por
distrito y CP, validaciones/estado, catálogo completo, autorización, guardar y
reemplazar varios CP por distrito, y tratamiento de errores de persistencia.
Se ejecutaron con PostgreSQL de Testcontainers: 7 aprobadas, sin fallos.

Reiniciar Gestión de Despachos para que el proceso que atiende HTTP use el
contrato nuevo y el mapeo de la tabla nueva.
