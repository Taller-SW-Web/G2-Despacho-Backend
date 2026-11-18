"""Exporta límites reproducibles y opcionalmente verifica topología en un PostGIS desechable."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time
import unicodedata
import uuid

RAIZ = Path(__file__).resolve().parents[1]


def normalizar(valor):
    return " ".join("".join(c for c in unicodedata.normalize("NFD", valor)
                            if unicodedata.category(c) != "Mn").upper().split())


def huella(contenido):
    return hashlib.sha256(contenido).hexdigest()


def serializar(valor):
    return (json.dumps(valor, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def exportar(origen, destino):
    procedencia = json.loads((RAIZ / "datos/geometrias/origen.json").read_text())
    contenido = origen.read_bytes()
    if huella(contenido) != procedencia["sha256"]:
        raise ValueError("La huella del origen no coincide con la revisión documentada")
    catalogo = json.loads((RAIZ / "datos/geometrias/catalogo.json").read_text())
    correspondencias = {}
    for provincia, nombres in catalogo.items():
        for indice, nombre in enumerate(nombres, 1):
            ubigeo = ("1501" if provincia == "Lima" else "0701") + f"{indice:02d}"
            correspondencias[(normalizar(provincia), normalizar(nombre))] = {
                "nombre": nombre, "provincia": provincia,
                "departamento": provincia, "ubigeo": ubigeo,
            }
    coleccion = json.loads(contenido)
    if coleccion["type"] != "FeatureCollection" or len(coleccion["features"]) != 50:
        raise ValueError("Se esperan 50 entidades en el origen")
    exportaciones = []
    usados = set()
    posiciones = 0
    for entidad in coleccion["features"]:
        propiedades = entidad["properties"]
        nombre = propiedades["distrito"]
        if propiedades["provincia"] == "CALLAO" and nombre == "MI PERÃz":
            nombre = "Mi Perú"
        distrito = correspondencias[(normalizar(propiedades["provincia"]), normalizar(nombre))]
        codigo = distrito["ubigeo"]
        if codigo in usados or entidad["geometry"]["type"] != "MultiPolygon":
            raise ValueError("Código duplicado o geometría inesperada")
        usados.add(codigo)
        for poligono in entidad["geometry"]["coordinates"]:
            for anillo in poligono:
                for posicion in anillo:
                    if len(posicion) != 2 or not all(math.isfinite(n) for n in posicion):
                        raise ValueError("Posición inválida")
                    posiciones += 1
        documento = {"type": "FeatureCollection", "features": [{
            "type": "Feature", "properties": distrito, "geometry": entidad["geometry"],
        }]}
        archivo = codigo + "_" + normalizar(distrito["nombre"]).lower().replace(" ", "_") + ".geojson"
        exportaciones.append((archivo, documento))
    if len(usados) != 50 or posiciones != 72489:
        raise ValueError("Cantidad de distritos o posiciones inesperada")
    destino.mkdir(parents=False, exist_ok=False)
    archivos = []
    entidades = []
    for archivo, documento in sorted(exportaciones):
        datos = serializar(documento)
        with (destino / archivo).open("xb") as salida:
            salida.write(datos)
        releido = json.loads((destino / archivo).read_bytes())
        if releido != documento:
            raise ValueError("El archivo exportado no conserva los datos")
        archivos.append({"archivo": archivo, "sha256": huella(datos), "bytes": len(datos)})
        entidades.extend(documento["features"])
    manifiesto = {**procedencia, "distritos": 50, "posiciones": posiciones, "archivos": archivos}
    (destino / "manifiesto.json").write_bytes(serializar(manifiesto))
    if huella(origen.read_bytes()) != procedencia["sha256"]:
        raise ValueError("El origen cambió durante la preparación")
    return manifiesto, entidades


def verificar_postgis(entidades):
    nombre = "f01-geometrias-" + uuid.uuid4().hex[:12]
    try:
        subprocess.run(["docker", "run", "--rm", "-d", "--name", nombre,
                        "--tmpfs", "/var/lib/postgresql/data",
                        "-e", "POSTGRES_PASSWORD=prueba_geografica",
                        "postgis/postgis:16-3.4"], check=True, capture_output=True)
        for _ in range(90):
            lista = subprocess.run(["docker", "exec", nombre, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                                   capture_output=True)
            if lista.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("PostGIS desechable no inició")
        datos = json.dumps(entidades, ensure_ascii=False, allow_nan=False).replace("'", "''")
        consulta = """CREATE EXTENSION IF NOT EXISTS postgis;
WITH datos AS (SELECT f->'properties'->>'ubigeo' codigo,
 ST_SetSRID(ST_GeomFromGeoJSON((f->'geometry')::text),4326) g
 FROM jsonb_array_elements('%s'::jsonb) f)
SELECT jsonb_build_object('ubigeo',codigo,'valida',ST_IsValid(g),
 'motivo',ST_IsValidReason(g),'posiciones',ST_NPoints(g))::text FROM datos ORDER BY codigo;
""" % datos
        resultado = subprocess.run(["docker", "exec", "-i", nombre, "psql", "-U", "postgres",
                                    "-qAt", "-v", "ON_ERROR_STOP=1"], input=consulta,
                                   text=True, capture_output=True)
        if resultado.returncode:
            raise RuntimeError("Falló la validación desechable: " + resultado.stderr)
        return [json.loads(linea) for linea in resultado.stdout.splitlines() if linea.startswith("{")]
    finally:
        subprocess.run(["docker", "rm", "-f", nombre], capture_output=True)


def main():
    argumentos = argparse.ArgumentParser(description=__doc__)
    argumentos.add_argument("--origen", required=True, type=Path)
    argumentos.add_argument("--destino", required=True, type=Path)
    argumentos.add_argument("--verificar-postgis", action="store_true")
    opciones = argumentos.parse_args()
    manifiesto, entidades = exportar(opciones.origen, opciones.destino)
    resumen = {"carpeta": str(opciones.destino), "distritos": manifiesto["distritos"],
               "posiciones": manifiesto["posiciones"], "sha256Origen": manifiesto["sha256"]}
    if opciones.verificar_postgis:
        resultados = verificar_postgis(entidades)
        (opciones.destino / "validacion-postgis.json").write_bytes(serializar(resultados))
        resumen["validosPostgis"] = sum(fila["valida"] for fila in resultados)
        resumen["invalidosPostgis"] = [fila for fila in resultados if not fila["valida"]]
        print(json.dumps(resumen, ensure_ascii=False, indent=2))
        return 0 if len(resultados) == 50 and all(f["valida"] for f in resultados) else 1
    print(json.dumps(resumen, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
