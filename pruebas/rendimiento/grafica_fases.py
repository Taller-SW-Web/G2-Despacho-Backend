#!/usr/bin/env python3
"""Gráfica SVG por fases a partir de un JTL: tasa real y p95 por ventana, con el mismo eje de tiempo.

Dos gráficas apiladas (no un eje doble): solicitudes por segundo y p95 de `elapsed` en ventanas de
N segundos. Cada fase medida es una franja con su nombre; los umbrales van como líneas punteadas.
Sin dependencias externas.

Ejemplo (PERF-06):
  python grafica_fases.py resultados/x/PERF-06.jtl resultados/x/fases.svg \
      --fase RUTA-BASE:500 --fase RUTA-PICO:1000 --fase RUTA-RECUPERACION:500 --titulo "PERF-06 · Mi Ruta"
"""
import argparse
import csv
import math
from pathlib import Path
from xml.sax.saxutils import escape

SUPERFICIE = "#fcfcfb"
TEXTO = "#0b0b0b"
TEXTO_2 = "#52514e"
REJILLA = "#e4e3df"
FRANJA = "#f0efec"
SERIE = "#2a78d6"

ANCHO, MARGEN_IZQ, MARGEN_DER = 880, 64, 24
ALTO_GRAFICA, SEPARACION, CABECERA = 200, 64, 72


def percentil(valores, p):
    ordenados = sorted(valores)
    return ordenados[max(0, math.ceil(p / 100 * len(ordenados)) - 1)]


def escala_bonita(maximo):
    """Tope del eje y con 4 divisiones redondas."""
    if maximo <= 0:
        return 1, 0.25
    paso_bruto = maximo / 4
    magnitud = 10 ** math.floor(math.log10(paso_bruto))
    paso = next(m * magnitud for m in (1, 2, 2.5, 5, 10) if m * magnitud >= paso_bruto)
    return paso * 4, paso


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jtl", type=Path)
    parser.add_argument("svg", type=Path)
    parser.add_argument("--fase", action="append", required=True, help="ETIQUETA[:UMBRAL_P95_MS]")
    parser.add_argument("--ventana", type=int, default=5, help="segundos por ventana (5)")
    parser.add_argument("--titulo", default="Tasa real y p95 por fase")
    argumentos = parser.parse_args()

    fases = []
    for especificacion in argumentos.fase:
        etiqueta, _, umbral = especificacion.partition(":")
        fases.append((etiqueta, int(umbral) if umbral else None))
    nombres = {etiqueta for etiqueta, _ in fases}
    with argumentos.jtl.open(encoding="utf-8", newline="") as archivo:
        filas = [f for f in csv.DictReader(archivo) if f["label"] in nombres]
    if not filas:
        raise SystemExit("El JTL no tiene muestras de las fases indicadas")

    inicio = min(int(f["timeStamp"]) for f in filas)
    fin = max(int(f["timeStamp"]) for f in filas)
    total_s = (fin - inicio) / 1000
    ventana = argumentos.ventana
    ventanas = {}
    for fila in filas:
        indice = (int(fila["timeStamp"]) - inicio) // (ventana * 1000)
        ventanas.setdefault(indice, []).append(int(fila["elapsed"]))
    puntos = [(i * ventana + ventana / 2, len(v) / ventana, percentil(v, 95)) for i, v in sorted(ventanas.items())]
    # La última ventana puede estar incompleta; no se dibuja para no inventar una caída de tasa.
    if puntos and (total_s - puntos[-1][0] + ventana / 2) < ventana:
        puntos = puntos[:-1]

    limites = {}
    for fila in filas:
        segundo = (int(fila["timeStamp"]) - inicio) / 1000
        desde, hasta = limites.get(fila["label"], (segundo, segundo))
        limites[fila["label"]] = (min(desde, segundo), max(hasta, segundo))

    ancho_util = ANCHO - MARGEN_IZQ - MARGEN_DER
    x = lambda s: MARGEN_IZQ + s / max(total_s, 1) * ancho_util
    alto = CABECERA + 2 * ALTO_GRAFICA + SEPARACION + 48
    partes = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{ANCHO}" height="{alto}" viewBox="0 0 {ANCHO} {alto}" '
              f'font-family="system-ui, -apple-system, Segoe UI, sans-serif" role="img" aria-label="{escape(argumentos.titulo)}">',
              f'<rect width="{ANCHO}" height="{alto}" fill="{SUPERFICIE}"/>',
              f'<text x="{MARGEN_IZQ}" y="28" font-size="17" font-weight="600" fill="{TEXTO}">{escape(argumentos.titulo)}</text>',
              f'<text x="{MARGEN_IZQ}" y="50" font-size="12.5" fill="{TEXTO_2}">Ventanas de {ventana} s · las franjas son las '
              f'fases medidas · las líneas punteadas son los umbrales de p95</text>']

    def grafica(arriba, titulo, valores, unidad, umbrales):
        maximo = max(max(valores), max((u for _, u in umbrales if u), default=0))
        tope, paso = escala_bonita(maximo * 1.1)
        y = lambda v: arriba + ALTO_GRAFICA - v / tope * ALTO_GRAFICA
        salida = [f'<text x="{MARGEN_IZQ}" y="{arriba - 12}" font-size="13" font-weight="600" fill="{TEXTO}">{escape(titulo)}</text>']
        for i, (etiqueta, _) in enumerate(fases):
            if etiqueta not in limites:
                continue
            desde, hasta = limites[etiqueta]
            if i % 2 == 1:
                salida.append(f'<rect x="{x(desde):.1f}" y="{arriba}" width="{x(hasta) - x(desde):.1f}" '
                              f'height="{ALTO_GRAFICA}" fill="{FRANJA}"/>')
            salida.append(f'<text x="{(x(desde) + x(hasta)) / 2:.1f}" y="{arriba + 14}" font-size="11" '
                          f'text-anchor="middle" fill="{TEXTO_2}">{escape(etiqueta)}</text>')
        for k in range(5):
            valor = paso * k
            salida.append(f'<line x1="{MARGEN_IZQ}" x2="{ANCHO - MARGEN_DER}" y1="{y(valor):.1f}" y2="{y(valor):.1f}" '
                          f'stroke="{REJILLA}" stroke-width="1"/>')
            texto = f"{valor:g}"
            salida.append(f'<text x="{MARGEN_IZQ - 8}" y="{y(valor) + 4:.1f}" font-size="11" text-anchor="end" '
                          f'fill="{TEXTO_2}">{texto}</text>')
        for etiqueta, umbral in umbrales:
            if umbral is None or etiqueta not in limites:
                continue
            desde, hasta = limites[etiqueta]
            salida.append(f'<line x1="{x(desde):.1f}" x2="{x(hasta):.1f}" y1="{y(umbral):.1f}" y2="{y(umbral):.1f}" '
                          f'stroke="{TEXTO_2}" stroke-width="1.5" stroke-dasharray="5 4"/>')
            salida.append(f'<text x="{x(hasta) - 4:.1f}" y="{y(umbral) - 5:.1f}" font-size="11" text-anchor="end" '
                          f'fill="{TEXTO_2}">umbral {umbral} {unidad}</text>')
        if unidad == "ms":
            # Etiqueta directa selectiva: el p95 de toda la fase, legible aunque la línea quede baja.
            for etiqueta, _ in fases:
                tiempos = [int(f["elapsed"]) for f in filas if f["label"] == etiqueta]
                if etiqueta in limites and tiempos:
                    desde, hasta = limites[etiqueta]
                    techo = max([v for (s, _, _), v in zip(puntos, valores) if desde <= s <= hasta] or [0])
                    salida.append(f'<text x="{(x(desde) + x(hasta)) / 2:.1f}" y="{y(techo) - 14:.1f}" font-size="12" '
                                  f'font-weight="600" text-anchor="middle" fill="{TEXTO}">p95 {percentil(tiempos, 95)} ms</text>')
        trazo = " ".join(f"{'M' if i == 0 else 'L'}{x(s):.1f},{y(v):.1f}" for i, (s, v) in enumerate(zip([p[0] for p in puntos], valores)))
        salida.append(f'<path d="{trazo}" fill="none" stroke="{SERIE}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>')
        for (s, _, _), v in zip(puntos, valores):
            # Objetivo de hover amplio con su valor; el punto visible es pequeño.
            salida.append(f'<g><title>{s - ventana / 2:.0f}–{s + ventana / 2:.0f} s: {v:g} {unidad}</title>'
                          f'<circle cx="{x(s):.1f}" cy="{y(v):.1f}" r="9" fill="transparent"/>'
                          f'<circle cx="{x(s):.1f}" cy="{y(v):.1f}" r="2.5" fill="{SERIE}" stroke="{SUPERFICIE}" stroke-width="1"/></g>')
        return salida

    arriba_1 = CABECERA + 24
    partes += grafica(arriba_1, "Tasa real (solicitudes por segundo)", [round(p[1], 2) for p in puntos], "sol/s", [])
    arriba_2 = arriba_1 + ALTO_GRAFICA + SEPARACION
    partes += grafica(arriba_2, "p95 del tiempo de respuesta (ms)", [p[2] for p in puntos], "ms", fases)
    base = arriba_2 + ALTO_GRAFICA
    for segundo in range(0, int(total_s) + 1, 30):
        partes.append(f'<text x="{x(segundo):.1f}" y="{base + 18}" font-size="11" text-anchor="middle" fill="{TEXTO_2}">{segundo} s</text>')
    partes.append(f'<text x="{ANCHO - MARGEN_DER}" y="{base + 38}" font-size="11" text-anchor="end" fill="{TEXTO_2}">'
                  f'Tiempo desde el inicio de la primera fase medida</text>')
    partes.append("</svg>")
    argumentos.svg.write_text("\n".join(partes), encoding="utf-8")
    print("Gráfica escrita en", argumentos.svg)


if __name__ == "__main__":
    main()
