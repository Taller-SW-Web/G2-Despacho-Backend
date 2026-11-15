# Preparación reproducible de geometrías de F-01

`origen.json` fija repositorio, revisión, huella y atribución. `catalogo.json` conserva la correspondencia proporcionada por el usuario; los códigos son `150101`–`150143` y `070101`–`070107`, según el orden de cada provincia. No se usan los identificadores numéricos del origen como identidad de Despacho.

Obtener el archivo detallado de la revisión indicada en `origen.json`, conservar sus licencias MIT/Apache-2.0 y ejecutar desde la raíz del backend, con Python 3 y Docker:

```bash
python3 herramientas/preparar_geometrias.py --origen /ruta/lima_callao_distritos.geojson --destino /ruta/carpeta-nueva --verificar-postgis
```

El padre de destino debe existir y la carpeta debe ser nueva. Se generan los 50 GeoJSON, `manifiesto.json` con sus huellas y `validacion-postgis.json`. La herramienta verifica el hash del origen, correspondencias, coordenadas conservadas y topología en un contenedor PostGIS desechable sin puertos, volúmenes persistentes ni acceso a Supabase. No repara límites ni importa a Gestión. Código de salida 1 si hay geometrías inválidas.

Los archivos individuales se cargan desde Distritos → Importar geometrías, declarando fuente y revisión del manifiesto. El backend vuelve a validar y requiere confirmación. La validez topológica no certifica vigencia oficial; esa revisión se registra separadamente. Estos artefactos son de preparación y no una migración automática ni un catálogo geográfico instalado.
