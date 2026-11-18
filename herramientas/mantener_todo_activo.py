#!/usr/bin/env python3
"""Levanta a la vez los tres scripts de mantener activo (Despacho, Seguridad y Productos).

Ejecuta cada script como un proceso aparte, con sus intervalos por defecto, y muestra
la salida de todos en una sola ventana con un prefijo por script. Ctrl+C los detiene a
todos. Solo usa Python estándar.
"""

import argparse
from pathlib import Path
import subprocess
import sys
import threading
import time

CARPETA = Path(__file__).resolve().parent
SCRIPTS = (
    ("Despacho ", "mantener_despacho_activo.py"),
    ("Seguridad", "mantener_seguridad_activo.py"),
    ("Productos", "mantener_productos_activo.py"),
)


def reenviar(prefijo, flujo, candado):
    for linea in flujo:
        with candado:
            print(f"[{prefijo}] {linea.rstrip()}", flush=True)


def main(argumentos=None):
    for flujo in (sys.stdout, sys.stderr):
        if hasattr(flujo, "reconfigure"):
            flujo.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--una-vez", action="store_true",
                        help="Cada script consulta una vez y termina; sale con 1 si alguno falló")
    opciones = parser.parse_args(argumentos)

    extra = ["--una-vez"] if opciones.una_vez else []
    candado = threading.Lock()
    procesos, hilos = [], []
    print("Levantando Despacho, Seguridad y Productos. Ctrl+C para detener todos.\n", flush=True)
    for prefijo, script in SCRIPTS:
        proceso = subprocess.Popen(
            [sys.executable, "-u", str(CARPETA / script), *extra],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace")
        hilo = threading.Thread(target=reenviar, args=(prefijo, proceso.stdout, candado), daemon=True)
        hilo.start()
        procesos.append(proceso)
        hilos.append(hilo)

    try:
        while any(proceso.poll() is None for proceso in procesos):
            time.sleep(0.5)
    except KeyboardInterrupt:
        # En la consola, Ctrl+C también llega a los hijos; se les da un momento para cerrar.
        fin = time.monotonic() + 5
        for proceso in procesos:
            try:
                proceso.wait(timeout=max(0.1, fin - time.monotonic()))
            except subprocess.TimeoutExpired:
                proceso.terminate()
        print("\nTodos los scripts detenidos.", flush=True)
        return 0
    for hilo in hilos:
        hilo.join(timeout=2)
    return 0 if all(proceso.returncode == 0 for proceso in procesos) else 1


if __name__ == "__main__":
    raise SystemExit(main())
