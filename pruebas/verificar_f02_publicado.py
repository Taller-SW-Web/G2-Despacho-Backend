#!/usr/bin/env python3
"""Verificación F-02 de solo lectura por Gateway; no crea datos ni carga archivos .env."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


class SinRedirecciones(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Nunca reenviar un Bearer a otro destino, ni interpretar un login HTML como éxito.
        return None


def validar_origen(valor):
    url = urllib.parse.urlsplit(valor)
    if (url.scheme not in ("http", "https") or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ("", "/")):
        raise ValueError("F02_GATEWAY debe ser un origen HTTP(S), sin credenciales, ruta ni parámetros")
    if url.scheme == "http" and url.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("HTTP sin TLS se permite únicamente para pruebas en loopback")
    return valor.rstrip("/")


def solicitar(origen, ruta, token=None):
    cabeceras = {"Accept": "application/json"}
    if token:
        cabeceras["Authorization"] = "Bearer " + token
    peticion = urllib.request.Request(origen + ruta, headers=cabeceras, method="GET")
    cliente = urllib.request.build_opener(SinRedirecciones())
    try:
        respuesta = cliente.open(peticion, timeout=10)
    except urllib.error.HTTPError as error:
        respuesta = error
    with respuesta:
        # No conservar cuerpos ni mensajes remotos, que pueden contener datos personales.
        cuerpo = respuesta.read(2_000_001)
        if len(cuerpo) > 2_000_000:
            raise ValueError("Respuesta demasiado grande")
        try:
            datos = json.loads(cuerpo)
        except (ValueError, UnicodeError):
            datos = None
        return respuesta.code, datos


def verificar(origen, gestor, repartidor, otro, despacho, jornada, consultar=solicitar):
    """Un despacho debe estar ASIGNADO antes del inicio de traslado. No verifica una mutación."""
    pasos = []
    def comprobar(nombre, ruta, token, esperado, validar=lambda datos: True):
        try:
            estado, datos = consultar(origen, ruta, token)
            correcto = estado == esperado and validar(datos)
            pasos.append({"paso": nombre, "http": estado, "correcto": bool(correcto)})
            return datos if correcto else None
        except Exception:
            pasos.append({"paso": nombre, "http": None, "correcto": False})
            return None

    objeto = lambda datos: isinstance(datos, dict)
    comprobar("salud_gateway", "/actuator/health", None, 200,
              lambda d: objeto(d) and d.get("status") == "UP")
    comprobar("cola_sin_token", "/api/v1/despachos/pendientes", None, 401)
    comprobar("ruta_sin_token", "/api/v1/repartidor/mi-ruta", None, 401)
    comprobar("cola_autorizada", "/api/v1/despachos/pendientes", gestor, 200,
              lambda d: objeto(d) and isinstance(d.get("contenido"), list)
              and isinstance(d.get("indicadores"), dict) and bool(d.get("fechaActualServidor")))
    segmento = urllib.parse.quote(jornada, safe="")
    ruta = comprobar("ruta_gestion", f"/api/v1/jornadas/{segmento}/ruta", gestor, 200,
        lambda d: objeto(d) and d.get("idJornada") == jornada and d.get("estadoJornada") == "ACTIVA"
        and isinstance(d.get("despachos"), list) and bool(d.get("versionRuta")))
    propia = comprobar("ruta_propia", "/api/v1/repartidor/mi-ruta", repartidor, 200,
        lambda d: objeto(d) and d.get("idJornada") == jornada and isinstance(d.get("despachos"), list))
    detalle = comprobar("ocupacion_jornada", f"/api/v1/jornadas/{segmento}", gestor, 200,
        lambda d: objeto(d) and d.get("idJornada") == jornada and d.get("estado") == "ACTIVA"
        and all(isinstance(d.get(campo), dict) and all(
            type(d[campo].get(dimension)) in (int, float) and d[campo][dimension] >= 0
            for dimension in ("pesoKg", "volumenM3", "paquetes"))
            for campo in ("limites", "ocupacion", "capacidadRemanente")))
    try:
        gestion = [p for p in ruta["despachos"] if p["idDespacho"] == despacho]
        campo = [p for p in propia["despachos"] if p["idDespacho"] == despacho]
        correcto = (len(gestion) == len(campo) == 1 and gestion[0]["estado"] == campo[0]["estado"] == "ASIGNADO"
                    and type(gestion[0]["posicion"]) is int and gestion[0]["posicion"] > 0
                    and gestion[0]["posicion"] == campo[0]["secuenciaRuta"]
                    and gestion[0]["idRepartidor"] == detalle["idRepartidor"])
        for dimension in ("pesoKg", "volumenM3", "paquetes"):
            limite, usado, libre = (detalle[c][dimension] for c in ("limites", "ocupacion", "capacidadRemanente"))
            carga = gestion[0][{"pesoKg": "pesoKg", "volumenM3": "volumenM3", "paquetes": "cantidadPaquetes"}[dimension]]
            correcto = correcto and limite > 0 and usado >= carga > 0 and abs(limite - usado - libre) < 1e-8
    except (TypeError, KeyError, IndexError):
        correcto = False
    pasos.append({"paso": "mismo_despacho_jornada_secuencia_y_ocupacion", "http": None, "correcto": bool(correcto)})
    # El contrato de F-03 no revela si un despacho ajeno existe: exige 404, no 403.
    comprobar("despacho_ajeno", "/api/v1/repartidor/despachos/" + urllib.parse.quote(despacho, safe=""), otro, 404)
    return pasos


def main():
    necesarias = ("F02_GATEWAY", "F02_GESTOR_TOKEN", "F02_REPARTIDOR_TOKEN", "F02_OTRO_REPARTIDOR_TOKEN",
                  "F02_DESPACHO", "F02_JORNADA")
    if any(not os.environ.get(campo, "").strip() for campo in necesarias):
        print("Faltan variables: " + ", ".join(c for c in necesarias if not os.environ.get(c, "").strip()), file=sys.stderr)
        return 2
    try:
        origen = validar_origen(os.environ["F02_GATEWAY"])
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    tokens = [os.environ[c] for c in necesarias[1:4]]
    if len(set(tokens)) != 3:
        print("Usar tres sesiones distintas: gestor, repartidor dueño y segundo repartidor", file=sys.stderr)
        return 2
    pasos = verificar(origen, *tokens, os.environ["F02_DESPACHO"], os.environ["F02_JORNADA"])
    correcto = all(p["correcto"] for p in pasos)
    print(json.dumps({"verificacion": "F-02 asignación ya preparada; solo lectura", "correcto": correcto,
        "fechaUtc": datetime.now(timezone.utc).isoformat(), "gateway": origen,
        "idDespacho": os.environ["F02_DESPACHO"], "idJornada": os.environ["F02_JORNADA"],
        "versionesDeclaradas": {r: os.environ.get("F02_SHA_" + r.upper()) for r in ("backend", "frontend", "docs")},
        "pasos": pasos, "limites": "No acredita login/MFA, reserva antes/después, captura en ambas apps, F-04 ni aceptación humana"},
        ensure_ascii=False, indent=2))
    return 0 if correcto else 1


if __name__ == "__main__":
    sys.exit(main())
