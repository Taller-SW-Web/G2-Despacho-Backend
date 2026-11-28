#!/usr/bin/env python3
"""Toma conteos reales antes/después de la ventana; no integra sus SQL al sampler medido."""
import argparse
import json
import time
from pathlib import Path
import importlib.util

spec = importlib.util.spec_from_file_location("reconciliacion", Path(__file__).with_name("reconciliar_persona_b.py"))
modulo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(modulo)
sql = modulo.sql


def main():
    p = argparse.ArgumentParser()
    p.add_argument("directorio", type=Path)
    a = p.parse_args()
    archivo = a.directorio / "ventanas.json"
    limite = time.monotonic() + 90
    ventanas = None
    while time.monotonic() < limite:
        try:
            ventanas = json.loads(archivo.read_text())
            break
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(.1)
    if ventanas is None:
        raise RuntimeError("JMeter no publicó un calendario válido en 90 segundos")
    fase = ventanas["COTIZACION-CARGA"]
    resultado = {}
    for nombre, instante in [("antes", fase["inicioMs"] / 1000 - .2),
                            ("despues", fase["inicioMs"] / 1000 + fase["duracionS"] + 11)]:
        time.sleep(max(0, instante - time.time()))
        resultado[nombre] = {"instanteUtcMs": time.time_ns() // 1_000_000,
            "filas": sql("despacho_gestion", "SELECT to_json(count(*)) FROM cotizaciones;")}
    (a.directorio / "conteos.json").write_text(json.dumps(resultado, indent=2))


if __name__ == "__main__":
    main()
