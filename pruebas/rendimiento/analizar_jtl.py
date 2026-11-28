#!/usr/bin/env python3
"""Analiza un JTL CSV de JMeter con el criterio común del plan de pruebas del hito 5.

Por fase (etiqueta): muestras, duración, tasa lograda, p50/p95/p99 de `elapsed` sobre todas las
muestras del intervalo, y errores separados (aserción de negocio, 429, 5xx, otros 4xx, red o tiempo
agotado). Un intervalo adicional NOMBRE@ultimosN filtra los últimos N segundos de una fase.

Ejemplo (PERF-06):
  python analizar_jtl.py resultados/x/PERF-06.jtl --fase RUTA-BASE:3:500 --fase RUTA-PICO:10:1000 \
      --fase RUTA-RECUPERACION:3 --fase RUTA-RECUPERACION@ultimos30:3:500
"""
import argparse
import csv
import json
import math
import sys
from pathlib import Path

UMBRAL_TASA = 0.95
UMBRAL_ERRORES = 0.01


def percentil(valores, p):
    """Percentil por rango más cercano sobre todas las muestras."""
    if not valores:
        return None
    ordenados = sorted(valores)
    return ordenados[max(0, math.ceil(p / 100 * len(ordenados)) - 1)]


def clasificar(fila):
    if fila["success"] == "true":
        return None
    codigo = fila["responseCode"]
    if codigo == "200":
        return "negocio"  # 200 con jornada, despachos o secuencia incorrectos
    if codigo == "429":
        return "429"
    if codigo.isdigit() and codigo.startswith("5"):
        return "5xx"
    if codigo.isdigit() and codigo.startswith("4"):
        return "4xx"
    return "red_o_tiempo_agotado"


def resumir(filas, tasa_objetivo, umbral_ms, ventana=None, inicio_ms=None, duracion_s=None):
    if inicio_ms is None or duracion_s is None or duracion_s <= 0:
        return {"muestras": 0, "resultado": "No concluyente",
                "motivo": "Faltan límites nominales de la fase; no se infieren de las muestras"}
    if ventana:
        inicio_ms += (duracion_s - ventana) * 1000
        duracion_s = ventana
    filas = [f for f in filas
             if inicio_ms <= int(f["timeStamp"]) < inicio_ms + duracion_s * 1000]
    if not filas:
        return {"muestras": 0, "resultado": "No concluyente", "motivo": "sin muestras"}
    inicio = min(int(f["timeStamp"]) for f in filas)
    fin = max(int(f["timeStamp"]) + int(f["elapsed"]) for f in filas)
    duracion = duracion_s
    tiempos = [int(f["elapsed"]) for f in filas]
    errores = {}
    motivos_negocio = {}
    for fila in filas:
        tipo = clasificar(fila)
        if tipo:
            errores[tipo] = errores.get(tipo, 0) + 1
            if tipo == "negocio":
                motivo = fila.get("failureMessage") or "sin detalle"
                motivos_negocio[motivo] = motivos_negocio.get(motivo, 0) + 1
    inesperados = sum(errores.values()) - errores.get("negocio", 0)
    tasa = len(filas) / duracion
    resumen = {
        "muestras": len(filas),
        "duracionS": round(duracion, 1),
        "tasaLograda": round(tasa, 3),
        "tasaObjetivo": tasa_objetivo,
        "p50": percentil(tiempos, 50),
        "p95": percentil(tiempos, 95),
        "p99": percentil(tiempos, 99),
        "max": max(tiempos),
        "errores": errores,
        "porcentajeErroresInesperados": round(100 * inesperados / len(filas), 3),
        "fallosNegocio": motivos_negocio,
        "umbralP95Ms": umbral_ms,
        "inicioMs": inicio,
        "finMs": fin,
    }
    incumplimientos = []
    if tasa_objetivo and tasa < UMBRAL_TASA * tasa_objetivo:
        incumplimientos.append(f"tasa {tasa:.2f}/s < 95 % de {tasa_objetivo}/s")
    if inesperados / len(filas) >= UMBRAL_ERRORES:
        incumplimientos.append("errores inesperados >= 1 %")
    if errores.get("negocio"):
        incumplimientos.append("respuestas exitosas con datos incorrectos")
    if umbral_ms is not None and resumen["p95"] >= umbral_ms:
        incumplimientos.append(f"p95 {resumen['p95']} ms >= {umbral_ms} ms")
    resumen["resultado"] = "Fallido" if incumplimientos else "Aprobado"
    resumen["incumplimientos"] = incumplimientos
    return resumen


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--fase", action="append", required=True,
                        help="ETIQUETA[@ultimosN]:TASA_POR_S[:UMBRAL_P95_MS]")
    parser.add_argument("--salida", type=Path, help="JSON con el resumen")
    parser.add_argument("--ventanas", type=Path, required=True,
                        help="JSON generado por JMeter: etiqueta -> inicioMs, duracionS")
    argumentos = parser.parse_args()
    ventanas = json.loads(argumentos.ventanas.read_text(encoding="utf-8"))

    with argumentos.jtl.open(encoding="utf-8", newline="") as archivo:
        filas = list(csv.DictReader(archivo))
    por_etiqueta = {}
    for fila in filas:
        por_etiqueta.setdefault(fila["label"], []).append(fila)

    resumen = {"jtl": str(argumentos.jtl), "fases": {}}
    for especificacion in argumentos.fase:
        partes = especificacion.split(":")
        nombre = partes[0]
        tasa = float(partes[1]) if len(partes) > 1 and partes[1] else None
        umbral = int(partes[2]) if len(partes) > 2 and partes[2] else None
        etiqueta, _, ventana = nombre.partition("@ultimos")
        muestras = por_etiqueta.get(etiqueta, [])
        segundos = int(ventana) if ventana else None
        limites = ventanas.get(etiqueta, {})
        resumen["fases"][nombre] = resumir(muestras, tasa, umbral, segundos,
                                          limites.get("inicioMs"), limites.get("duracionS"))

    resultados = [fase["resultado"] for fase in resumen["fases"].values()]
    resumen["resultado"] = ("No concluyente" if "No concluyente" in resultados
                            else "Fallido" if "Fallido" in resultados else "Aprobado")

    print(f"{'Fase':34} {'Muestras':>8} {'Tasa/s':>7} {'p50':>5} {'p95':>5} {'p99':>5} {'Err%':>6}  Resultado")
    for nombre, fase in resumen["fases"].items():
        if not fase["muestras"]:
            print(f"{nombre:34} {0:>8}  {fase['resultado']}")
            continue
        print(f"{nombre:34} {fase['muestras']:>8} {fase['tasaLograda']:>7} {fase['p50']:>5} {fase['p95']:>5} "
              f"{fase['p99']:>5} {fase['porcentajeErroresInesperados']:>6}  {fase['resultado']} "
              + "; ".join(fase["incumplimientos"]))
    print("Resultado:", resumen["resultado"])
    if argumentos.salida:
        argumentos.salida.write_text(json.dumps(resumen, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if resumen["resultado"] == "Aprobado" else 1


if __name__ == "__main__":
    sys.exit(main())
