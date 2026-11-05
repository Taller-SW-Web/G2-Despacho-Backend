#!/usr/bin/env python3
"""Prueba de lectura de F-04 por Gateway, local desechable o despliegue indicado."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


RAIZ = Path(__file__).resolve().parents[1]
RUTA_F04 = "/api/v1/despachos/fallidos?pagina=1&tamanoPagina=1"


class SinRedireccion(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def solicitar(url, token=None, formulario=None):
    cabeceras = {"Accept": "application/json, application/problem+json"}
    if token:
        cabeceras["Authorization"] = "Bearer " + token
    datos = None
    if formulario is not None:
        datos = urllib.parse.urlencode(formulario).encode()
        cabeceras["Content-Type"] = "application/x-www-form-urlencoded"
    peticion = urllib.request.Request(url, data=datos, headers=cabeceras)
    cliente = urllib.request.build_opener(SinRedireccion())
    try:
        respuesta = cliente.open(peticion, timeout=10)
    except urllib.error.HTTPError as error:
        respuesta = error
    with respuesta:
        contenido = respuesta.read()
        try:
            documento = json.loads(contenido)
        except (ValueError, UnicodeDecodeError):
            documento = None
        return respuesta.code, respuesta.headers.get_content_type(), documento


def comprobar(gateway, gestor, sin_permiso):
    resultados = []
    casos = [
        ("salud_gateway", "/actuator/health", None, 200),
        ("f04_sin_token", RUTA_F04, None, 401),
        ("f04_sin_permiso", RUTA_F04, sin_permiso, 403),
        ("f04_gestor", RUTA_F04, gestor, 200),
    ]
    for nombre, ruta, token, esperado in casos:
        resultado = {"caso": nombre, "ruta": ruta, "esperado": esperado, "obtenido": None,
                     "aprobado": False}
        try:
            estado, tipo, datos = solicitar(gateway + ruta, token)
            resultado["obtenido"] = estado
            valido = estado == esperado and isinstance(datos, dict)
            if nombre == "salud_gateway":
                valido = valido and datos.get("status") == "UP"
            elif esperado in (401, 403):
                valido = valido and tipo == "application/problem+json" and datos.get("status") == esperado
                valido = valido and isinstance(datos.get("code"), str) and bool(datos["code"])
                valido = valido and "contenido" not in datos
            else:
                valido = valido and tipo == "application/json" and isinstance(datos.get("contenido"), list)
                valido = valido and type(datos.get("totalElementos")) is int and datos["totalElementos"] >= 0
                valido = valido and datos.get("pagina") == 1 and datos.get("tamanoPagina") == 1
            resultado["aprobado"] = bool(valido)
            resultado["detalle"] = "Respuesta esperada" if valido else "Estado o estructura inesperados"
        except (OSError, urllib.error.URLError, ValueError):
            resultado["detalle"] = "No se pudo completar la consulta HTTP"
        resultados.append(resultado)
    return resultados


def esperar_salud(url):
    limite = time.monotonic() + 180
    while time.monotonic() < limite:
        try:
            estado, _, datos = solicitar(url)
            if estado == 200 and isinstance(datos, dict) and datos.get("status") == "UP":
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(2)
    raise RuntimeError("El servicio local no alcanzó el estado UP")


@contextmanager
def entorno_local():
    motor = subprocess.run(["docker", "info"], capture_output=True)
    if motor.returncode != 0:
        raise RuntimeError("El motor de Docker no está disponible")
    for servicio in ("api-gateway", "servicio-gestion-despachos", "servicio-operacion-reparto"):
        jar = RAIZ / servicio / "target" / (servicio + "-0.0.1-SNAPSHOT.jar")
        if not jar.is_file():
            raise RuntimeError("Construir primero con clean verify el servicio " + servicio)
    proyecto = "humo-f04-" + uuid.uuid4().hex[:12]
    compose = ["docker", "compose", "--env-file", os.devnull, "-p", proyecto,
               "-f", str(RAIZ / "pruebas/compose.f02.yaml")]
    print("Preparando servicios y bases desechables con Seguridad local", flush=True)
    try:
        subprocess.run(compose + ["up", "--build", "-d"], cwd=RAIZ, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        seguridad = "http://127.0.0.1:18090"
        gateway = "http://127.0.0.1:18080"
        esperar_salud(seguridad + "/actuator/health")
        esperar_salud(gateway + "/actuator/health")
        for servicio, puerto in (("gestion", 8081), ("operacion", 8082)):
            limite = time.monotonic() + 180
            while time.monotonic() < limite:
                salud = subprocess.run(compose + ["exec", "-T", "seguridad-local", "curl", "--fail",
                    "--silent", f"http://{servicio}:{puerto}/actuator/health"], capture_output=True)
                if salud.returncode == 0:
                    break
                time.sleep(2)
            else:
                raise RuntimeError("No inició el proveedor local " + servicio)
        tokens = []
        for perfil in ("gestor", "sin-rol"):
            estado, _, datos = solicitar(seguridad + "/pruebas/token-usuario", formulario={"perfil": perfil})
            if estado != 200 or not isinstance(datos, dict) or not isinstance(datos.get("accessToken"), str):
                raise RuntimeError("No se pudo emitir la identidad de prueba")
            tokens.append(datos["accessToken"])
        yield gateway, tokens[0], tokens[1]
    finally:
        subprocess.run(compose + ["down", "--remove-orphans"], cwd=RAIZ, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def origen_gateway(valor):
    url = urllib.parse.urlsplit(valor)
    if (url.scheme not in ("http", "https") or not url.hostname or url.username or url.password
            or url.path not in ("", "/") or url.query or url.fragment):
        raise argparse.ArgumentTypeError("Usar el origen del Gateway, sin ruta, credenciales ni parámetros")
    return valor.rstrip("/")


def main():
    argumentos = argparse.ArgumentParser(description=__doc__)
    modo = argumentos.add_mutually_exclusive_group(required=True)
    modo.add_argument("--local", action="store_true", help="Levantar y retirar el entorno desechable de pruebas")
    modo.add_argument("--gateway-url", type=origen_gateway, help="Origen del Gateway publicado o local existente")
    argumentos.add_argument("--salida", type=Path, default=RAIZ / "pruebas/resultados/humo-f04.json")
    argumentos.add_argument("--version-desplegada", help="SHA o revisión que confirmó el responsable del despliegue")
    opciones = argumentos.parse_args()
    gestor = os.environ.get("HUMO_TOKEN_GESTOR", "").strip()
    sin_permiso = os.environ.get("HUMO_TOKEN_SIN_PERMISO", "").strip()
    if not opciones.local and (not gestor or not sin_permiso):
        argumentos.error("Definir HUMO_TOKEN_GESTOR y HUMO_TOKEN_SIN_PERMISO en el entorno del proceso")
    version = subprocess.run(["git", "rev-parse", "HEAD"], cwd=RAIZ, capture_output=True, text=True)
    reporte = {"fechaUtc": datetime.now(timezone.utc).isoformat(), "commitVerificador": version.stdout.strip(),
               "versionDesplegada": opciones.version_desplegada,
               "entorno": "local desechable con Seguridad temporal" if opciones.local else "Gateway indicado",
               "gateway": opciones.gateway_url, "aprobado": False, "casos": []}
    try:
        if opciones.local:
            with entorno_local() as (gateway, gestor, sin_permiso):
                reporte["gateway"] = gateway
                reporte["casos"] = comprobar(gateway, gestor, sin_permiso)
        else:
            reporte["casos"] = comprobar(opciones.gateway_url, gestor, sin_permiso)
        reporte["aprobado"] = all(caso["aprobado"] for caso in reporte["casos"])
    except RuntimeError as error:
        reporte["error"] = str(error)
    except (OSError, subprocess.CalledProcessError):
        reporte["error"] = "No se pudo preparar o retirar el entorno local; comprobar Docker, JAR y puertos 18080/18090"
    opciones.salida.parent.mkdir(parents=True, exist_ok=True)
    opciones.salida.write_text(json.dumps(reporte, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for caso in reporte["casos"]:
        print(f"{caso['caso']}: esperado={caso['esperado']} obtenido={caso['obtenido']} aprobado={caso['aprobado']}")
    print("Resultado: " + ("APROBADO" if reporte["aprobado"] else "FALLIDO"))
    print("Informe: " + str(opciones.salida))
    return 0 if reporte["aprobado"] else 1


if __name__ == "__main__":
    sys.exit(main())
