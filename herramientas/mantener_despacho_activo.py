#!/usr/bin/env python3
"""Mantiene activos los backends de Despacho (Gateway, Gestión y Operación) consultando su salud.

Render apaga los servicios del plan free tras 15 minutos sin tráfico; este script consulta
/actuator/health de cada uno periódicamente. Solo lectura: no envía tokens ni modifica nada.
Independiente: solo usa Python estándar.
"""

import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import http.client
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SERVICIOS = (
    ("Gateway", "https://taller-api-gateway.onrender.com/actuator/health", "UP"),
    ("Gestión", "https://taller-servicio-gestion-despachos.onrender.com/actuator/health", "UP"),
    ("Operación", "https://taller-servicio-operacion-reparto.onrender.com/actuator/health", "UP"),
)
DEPENDENCIAS_COTIZACION = (
    ("Seguridad (G7)", "https://g7-auth-service.onrender.com/actuator/health", "UP"),
    ("Productos (G6)", "https://api-gateway.calmgrass-aec0b33c.chilecentral.azurecontainerapps.io/health", "ok"),
)

# --- Lógica de consulta (igual en los tres scripts) ---

INTERVALO_MINIMO = 60


class SinRedireccion(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validar_url(valor):
    partes = urllib.parse.urlsplit(valor)
    if (partes.scheme not in ("http", "https") or not partes.hostname
            or partes.username is not None or partes.password is not None
            or partes.query or partes.fragment):
        raise argparse.ArgumentTypeError("Usa una URL HTTP(S) sin credenciales ni parámetros")
    if partes.scheme == "http" and partes.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise argparse.ArgumentTypeError("Usa HTTPS; HTTP solo está permitido en loopback")
    return valor


def segundos_retry_after(valor):
    if not valor:
        return 0
    if valor.strip().isdigit():
        return int(valor.strip())
    try:
        fecha = parsedate_to_datetime(valor)
        if fecha.tzinfo is None:
            fecha = fecha.replace(tzinfo=timezone.utc)
        return max(0, (fecha - datetime.now(timezone.utc)).total_seconds())
    except (TypeError, ValueError, OverflowError):
        return 0


def consultar(url, timeout, salud_esperada="UP"):
    """GET a la URL de salud. Devuelve (correcto, detalle, segundos_de_espera, duracion)."""
    inicio = time.monotonic()
    peticion = urllib.request.Request(url, headers={
        "Accept": "application/json", "User-Agent": "Despacho-Salud/1.0",
    }, method="GET")
    cliente = urllib.request.build_opener(SinRedireccion())
    try:
        try:
            respuesta = cliente.open(peticion, timeout=timeout)
        except urllib.error.HTTPError as error:
            respuesta = error
        with respuesta:
            estado = respuesta.code
            espera = segundos_retry_after(respuesta.headers.get("Retry-After"))
            contenido = respuesta.read(65537)
        salud = None
        if len(contenido) <= 65536:
            try:
                datos = json.loads(contenido)
                if isinstance(datos, dict):
                    salud = datos.get("status")
            except (ValueError, UnicodeDecodeError):
                pass
        correcto = estado == 200 and salud == salud_esperada
        detalle = f"HTTP {estado}; " + (f"salud {salud_esperada}" if correcto else "salud no confirmada")
        return correcto, detalle, espera, time.monotonic() - inicio
    except (OSError, urllib.error.URLError, http.client.HTTPException, ValueError) as error:
        return False, f"sin respuesta ({type(error).__name__})", 0, time.monotonic() - inicio


def numero_positivo(valor):
    numero = float(valor)
    if not math.isfinite(numero) or numero <= 0:
        raise argparse.ArgumentTypeError("Debe ser un número finito mayor que cero")
    return numero


def preparar_salida():
    for flujo in (sys.stdout, sys.stderr):
        if hasattr(flujo, "reconfigure"):
            flujo.reconfigure(encoding="utf-8", errors="replace")


def crear_parser(descripcion, intervalo, timeout=75):
    parser = argparse.ArgumentParser(description=descripcion)
    parser.add_argument("--intervalo", type=numero_positivo, default=intervalo,
                        help=f"Segundos entre consultas por destino; mínimo {INTERVALO_MINIMO} "
                             f"(por defecto: {intervalo:g})")
    parser.add_argument("--timeout", type=numero_positivo, default=timeout,
                        help=f"Espera máxima por petición, incluyendo arranques lentos "
                             f"(por defecto: {timeout:g})")
    parser.add_argument("--una-vez", action="store_true", help="Consulta todos una vez y termina")
    return parser


def validar_opciones(parser, opciones):
    if opciones.intervalo < INTERVALO_MINIMO:
        parser.error(f"El intervalo mínimo es {INTERVALO_MINIMO} segundos para evitar exceso de solicitudes")


def ejecutar(destinos, opciones, consultor=None):
    """Bucle común: consulta cada destino según su intervalo y respeta Retry-After."""
    consultor = consultor or consultar
    destinos = tuple(destinos)
    proximas = {url: 0 for _, url, _ in destinos}
    print(f"Consultando {len(destinos)} destino(s) cada {opciones.intervalo:g} s. "
          f"Ctrl+C para detener.", flush=True)
    try:
        while True:
            todos_correctos = True
            for nombre, url, esperado in destinos:
                if time.monotonic() < proximas[url]:
                    continue
                print(f"Consultando {nombre}...", flush=True)
                correcto, detalle, espera, duracion = consultor(url, opciones.timeout, esperado)
                # Respeta Retry-After sin bloquear las consultas de los demás destinos.
                demora = max(opciones.intervalo, espera)
                proximas[url] = time.monotonic() + demora
                todos_correctos = todos_correctos and correcto
                fecha = datetime.now().astimezone().isoformat(timespec="seconds")
                marca = "OK" if correcto else "AVISO"
                print(f"{fecha} [{marca}] {nombre}: {detalle} ({duracion:.1f} s). "
                      f"Próxima consulta en {demora:.0f} s.", flush=True)
            if opciones.una_vez:
                return 0 if todos_correctos else 1
            time.sleep(max(0, min(proximas.values()) - time.monotonic()))
    except KeyboardInterrupt:
        print("\nConsultas detenidas.", flush=True)
        return 0


# --- Programa ---

def main(argumentos=None):
    preparar_salida()
    parser = crear_parser(__doc__, intervalo=300)
    destinos = parser.add_mutually_exclusive_group()
    destinos.add_argument("--url", action="append", type=validar_url,
                          help="URL completa de salud; repetir para sustituir los tres destinos predeterminados")
    destinos.add_argument("--con-dependencias", action="store_true",
                          help="Agrega las consultas de salud de Seguridad (G7) y Productos (G6)")
    opciones = parser.parse_args(argumentos)
    validar_opciones(parser, opciones)
    servicios = SERVICIOS
    if opciones.url:
        servicios = tuple((urllib.parse.urlsplit(url).netloc, url, "UP")
                          for url in dict.fromkeys(opciones.url))
    elif opciones.con_dependencias:
        servicios += DEPENDENCIAS_COTIZACION
    return ejecutar(servicios, opciones)


if __name__ == "__main__":
    raise SystemExit(main())
