#!/usr/bin/env python3
"""Contrato de recepción por Gateway y liberación real en un entorno desechable."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

from verificar_humo_f04 import RAIZ, SinRedireccion, entorno_local_con_comandos, solicitar


class IncumplimientoContrato(RuntimeError):
    pass


def exigir(condicion, mensaje):
    if not condicion:
        raise IncumplimientoContrato(mensaje)


def consultar_sql(compose, base, sentencia):
    resultado = subprocess.run(compose + ["exec", "-T", "postgres", "psql", "-XAt",
        "-v", "ON_ERROR_STOP=1", "-U", "prueba", "-d", base], input=sentencia,
        text=True, encoding="utf-8", capture_output=True, check=True)
    return resultado.stdout.strip()


def validar_problema(documento, estado):
    exigir(documento.get("status") == estado and isinstance(documento.get("code"), str)
           and bool(documento["code"]), "Error sin estado o código contractual")
    exigir(all(isinstance(documento.get(campo), str) and bool(documento[campo])
               for campo in ("title", "detail", "instance", "correlationId", "timestamp")),
           "Problem Details incompleto")
    # RFC 7807 permite omitir type; en ese caso su valor implícito es about:blank.
    exigir("type" not in documento or (isinstance(documento["type"], str) and bool(documento["type"])),
           "Tipo de problema inválido")
    exigir(not ({"recepcion", "incidencia", "historial", "contenido"} & documento.keys()),
           "El rechazo expone datos protegidos")


def peticion(url, token, cuerpo=None, esperado=200, codigo=None):
    cabeceras = {"Accept": "application/json, application/problem+json"}
    if token:
        cabeceras["Authorization"] = "Bearer " + token
    datos = None
    if cuerpo is not None:
        datos = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
        cabeceras["Content-Type"] = "application/json"
    cliente = urllib.request.build_opener(SinRedireccion())
    try:
        respuesta = cliente.open(urllib.request.Request(url, data=datos, headers=cabeceras), timeout=10)
    except urllib.error.HTTPError as error:
        respuesta = error
    with respuesta:
        exigir(respuesta.code == esperado, f"HTTP esperado {esperado}, obtenido {respuesta.code}")
        documento = json.load(respuesta)
        exigir(isinstance(documento, dict), "El cuerpo debe ser un objeto JSON")
        if esperado >= 400:
            exigir(respuesta.headers.get_content_type() == "application/problem+json", "Falta Problem Details")
            validar_problema(documento, esperado)
            if esperado == 401:
                exigir(respuesta.headers.get("WWW-Authenticate", "").startswith("Bearer"),
                       "El 401 debe informar WWW-Authenticate Bearer")
            if codigo:
                exigir(documento["code"] == codigo, "Código de error diferente al contrato")
            if esperado == 400:
                exigir(bool(documento.get("errores")), "Faltan errores por campo")
        else:
            exigir(respuesta.headers.get_content_type() == "application/json", "Respuesta sin JSON")
            if esperado == 202:
                exigir(respuesta.headers.get("Cache-Control") == "no-store", "Recepción sin Cache-Control no-store")
        return documento


def preparar_caso(compose, api, gestor):
    zona, distrito, perfil, vehiculo = (str(uuid.uuid4()) for _ in range(4))
    actor = "11111111-1111-4111-8111-111111111111"
    consultar_sql(compose, "despacho_gestion", f"""
        INSERT INTO distrito(id,nombre) VALUES ('{distrito}','Distrito sintético F04');
        INSERT INTO zonas(id,nombre,estado) VALUES ('{zona}','Zona sintética F04','ACTIVO');
        INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('{zona}','{distrito}');
        INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('{zona}',10,1);
    """)
    consultar_sql(compose, "despacho_operacion", f"""
        INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,
            estado_registro,id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
        VALUES ('{perfil}','Contrato','Prueba','12345678','999111222','f04@example.invalid','F04-TEST',
            'COMPLETO','ACTIVO','22222222-2222-4222-8222-222222222222','VINCULADO','{actor}','{actor}');
        INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
        VALUES ('{vehiculo}','F04-TEST','DISPONIBLE',500,4,80,'{actor}','{actor}');
    """)
    # Fecha civil de Lima sin depender de la zona horaria del host ni del paquete tzdata.
    hoy = datetime.now(timezone(timedelta(hours=-5))).date().isoformat()
    jornada = peticion(api + "/jornadas", gestor, {"idRepartidor": perfil, "idFurgoneta": vehiculo,
        "idZona": zona, "fecha": hoy}, esperado=201)["idJornada"]
    despacho = peticion(api + "/despachos/simulaciones", gestor,
        {"idZona": zona, "pesoKg": 2, "volumenM3": 0.01}, esperado=201)["idDespacho"]
    peticion(api + f"/despachos/{despacho}/asignacion", gestor,
        {"idRepartidor": perfil, "idJornada": jornada})
    estado, _, credencial = solicitar("http://127.0.0.1:18090/api/v1/auth/token", formulario={
        "grant_type": "client_credentials", "client_id": "modulo-despacho",
        "client_secret": "despacho-local-no-produccion"})
    exigir(estado == 200, "No se pudo preparar la identidad técnica temporal")
    cuerpo = {"estadoDestino": "FALLIDO", "idRepartidor": perfil, "idJornada": jornada,
        "motivo": "NO_INTENTADO", "comentario": "Caso sintético de contrato F04",
        "ejecutor": perfil, "origen": "MANUAL"}
    # Ruta privada dentro de la red temporal. La credencial se entrega por stdin, nunca como argumento.
    configuracion = 'url = "http://gestion:8081/internal/v1/despachos/' + despacho + '/transiciones"\n'
    configuracion += 'header = "Authorization: Bearer ' + credencial["accessToken"] + '"\n'
    configuracion += 'header = "Idempotency-Key: ' + str(uuid.uuid4()) + '"\n'
    configuracion += 'header = "Content-Type: application/json"\n'
    configuracion += 'data = ' + json.dumps(json.dumps(cuerpo)) + '\n'
    resultado = subprocess.run(compose + ["exec", "-T", "seguridad-local", "curl", "--fail",
        "--silent", "--config", "-"], input=configuracion, text=True, capture_output=True, check=True)
    exigir(json.loads(resultado.stdout).get("estado") == "FALLIDO", "No se preparó la incidencia")
    return despacho


def instantanea(compose, despacho):
    # Solo lectura de cada base del proyecto temporal que creó esta ejecución.
    gestion = consultar_sql(compose, "despacho_gestion", f"""
        SELECT json_build_object('estado',estado,'version',version,'intentos',numero_intentos,
            'recepciones',(SELECT count(*) FROM intentos_entrega WHERE despacho_id=d.id AND recibido_en_centro_en IS NOT NULL),
            'historial',(SELECT count(*) FROM historial_estados_despacho WHERE despacho_id=d.id AND tipo_operacion='RECEPCION_EN_CENTRO'),
            'solicitudes',(SELECT count(*) FROM liberaciones_capacidad WHERE despacho_id=d.id),
            'eventos',(SELECT count(*) FROM eventos_salientes WHERE despacho_id=d.id))
        FROM despachos d WHERE id='{despacho}';
    """)
    operacion = consultar_sql(compose, "despacho_operacion", f"""
        SELECT coalesce(json_agg(json_build_object('id',id,'estado',estado,'liberadoEn',liberado_en)
            ORDER BY id),'[]'::json) FROM reservas_capacidad WHERE despacho_id_externo='{despacho}';
    """)
    ocupacion = consultar_sql(compose, "despacho_operacion", f"""
        SELECT json_build_object('pesoKg',coalesce(sum(peso_kg),0),
            'volumenM3',coalesce(sum(volumen_m3),0),'paquetes',coalesce(sum(cantidad_paquetes),0))
        FROM reservas_capacidad WHERE estado='ACTIVA' AND asignacion_diaria_id IN
            (SELECT asignacion_diaria_id FROM reservas_capacidad WHERE despacho_id_externo='{despacho}');
    """)
    return {"gestion": json.loads(gestion), "reservas": json.loads(operacion), "ocupacion": json.loads(ocupacion)}


def validar_recepcion(original, cuerpo, despacho):
    exigir(original.get("idDespacho") == despacho and original.get("idIncidencia") == cuerpo["idIncidencia"],
           "Identidad de recepción incorrecta")
    exigir(original.get("estado") == "FALLIDO" and original.get("versionDespacho") == cuerpo["versionDespacho"] + 1,
           "Estado o versión posterior incorrectos")
    recepcion = original["recepcion"]
    exigir(recepcion["estado"] == "RECIBIDO_EN_CENTRO" and recepcion["selloIntacto"] is False
           and recepcion["observacion"] is None and recepcion["idUsuario"] == "11111111-1111-4111-8111-111111111111",
           "El retorno o la identidad del gestor no se conservaron")
    exigir(datetime.fromisoformat(recepcion["confirmadaEn"].replace("Z", "+00:00")).utcoffset() == timedelta(0),
           "La fecha de recepción debe estar en UTC")
    liberacion = recepcion["liberacionCapacidad"]
    exigir(bool(liberacion["idSolicitud"]) and liberacion["estado"] == "PENDIENTE"
           and liberacion["requiereRevision"] is False and liberacion["confirmadaEn"] is None,
           "El 202 debe conservar la liberación pendiente original")
    exigir(datetime.fromisoformat(liberacion["actualizadaEn"].replace("Z", "+00:00")).utcoffset() == timedelta(0),
           "La fecha de sincronización debe estar en UTC")


def verificar_persistencia(antes, despues):
    esperado = dict(antes["gestion"], version=antes["gestion"]["version"] + 1,
                    recepciones=1, historial=1, solicitudes=1)
    exigir(despues["gestion"] == esperado, "Recepción con efectos duplicados o cambios ajenos al contrato")
    exigir(len(despues["reservas"]) == 1 and despues["reservas"][0]["id"] == antes["reservas"][0]["id"]
           and despues["reservas"][0]["estado"] == "LIBERADA" and bool(despues["reservas"][0]["liberadoEn"]),
           "No se liberó únicamente la reserva original")
    exigir(despues["ocupacion"] == {"pesoKg": 0, "volumenM3": 0, "paquetes": 0},
           "La ocupación sintética no se redujo a cero")


def ejecutar_contrato(compose, gateway, gestor, sin_permiso, reporte):
    api = gateway + "/api/v1"
    despacho = preparar_caso(compose, api, gestor)
    ruta = api + f"/despachos/{despacho}"
    detalle = peticion(ruta + "/incidencia", gestor)
    cuerpo = {"idIncidencia": detalle["incidencia"]["idIncidencia"],
        "versionDespacho": detalle["versionDespacho"], "selloIntacto": False}
    antes = instantanea(compose, despacho)
    exigir(antes["gestion"]["recepciones"] == 0 and antes["gestion"]["historial"] == 0
           and antes["gestion"]["solicitudes"] == 0 and len(antes["reservas"]) == 1
           and antes["reservas"][0]["estado"] == "ACTIVA"
           and antes["ocupacion"] == {"pesoKg": 2, "volumenM3": 0.01, "paquetes": 1},
           "Caso inicial sin reserva activa o ya recibido")
    reporte["idDespachoSintetico"] = despacho
    reporte["persistenciaInicial"] = antes

    def caso(nombre, accion):
        resultado = {"caso": nombre, "aprobado": False}
        reporte["casos"].append(resultado)
        accion()
        resultado["aprobado"] = True
        print(nombre + ": APROBADO", flush=True)

    def rechazar(token, contenido, estado, codigo=None):
        peticion(ruta + "/recepcion-centro", token, contenido, estado, codigo)
        exigir(instantanea(compose, despacho) == antes, "Un rechazo alteró datos o capacidad")

    caso("sin_token_401_sin_cambios", lambda: rechazar(None, cuerpo, 401))
    caso("sin_permiso_403_sin_cambios", lambda: rechazar(sin_permiso, cuerpo, 403))
    invalidos = {
        "sello_omitido": {k: v for k, v in cuerpo.items() if k != "selloIntacto"},
        "sello_nulo": dict(cuerpo, selloIntacto=None),
        "sello_texto": dict(cuerpo, selloIntacto="false"),
        "version_decimal": dict(cuerpo, versionDespacho=float(cuerpo["versionDespacho"])),
        "campo_ajeno": dict(cuerpo, usuario="ajeno"),
        "observacion_excesiva": dict(cuerpo, observacion="😀" * 501),
    }
    for nombre, contenido in invalidos.items():
        caso(nombre + "_400_sin_cambios", lambda contenido=contenido:
             rechazar(gestor, contenido, 400, "DESP_ERROR_DATOS_RECEPCION_INVALIDOS"))
    caso("version_obsoleta_409_sin_cambios", lambda: rechazar(gestor,
        dict(cuerpo, versionDespacho=cuerpo["versionDespacho"] - 1), 409, "DESP_ERROR_DESPACHO_DESACTUALIZADO"))
    caso("incidencia_ajena_409_sin_cambios", lambda: rechazar(gestor,
        dict(cuerpo, idIncidencia=str(uuid.uuid4())), 409, "DESP_ERROR_DESPACHO_DESACTUALIZADO"))
    original = {}

    def recibir():
        original.update(peticion(ruta + "/recepcion-centro", gestor, cuerpo, 202))
        validar_recepcion(original, cuerpo, despacho)

    caso("recepcion_202_sello_false_sin_observacion", recibir)

    def liberar():
        limite = time.monotonic() + 90
        while time.monotonic() < limite:
            actual = peticion(ruta + "/incidencia", gestor)
            if actual["incidencia"]["recepcion"]["liberacionCapacidad"]["estado"] == "CONFIRMADA":
                verificar_persistencia(antes, instantanea(compose, despacho))
                return
            time.sleep(1)
        raise IncumplimientoContrato("La liberación real no se confirmó en 90 segundos")

    caso("liberacion_real_reserva_original", liberar)
    despues = instantanea(compose, despacho)

    def repetir():
        repetida = peticion(ruta + "/recepcion-centro", gestor, cuerpo, 202)
        exigir(repetida == original, "La repetición no devolvió la respuesta original")
        exigir(instantanea(compose, despacho) == despues, "La repetición duplicó historial o liberación")

    caso("repeticion_202_respuesta_original_sin_duplicados", repetir)

    def conflicto():
        peticion(ruta + "/recepcion-centro", gestor, dict(cuerpo, selloIntacto=True), 409,
                 "DESP_ERROR_OPERACION_REUTILIZADA")
        exigir(instantanea(compose, despacho) == despues, "El conflicto sobrescribió la recepción")

    caso("contenido_distinto_409_sin_sobrescritura", conflicto)
    reporte["persistenciaFinal"] = despues


def main():
    argumentos = argparse.ArgumentParser(description=__doc__)
    argumentos.add_argument("--salida", type=Path, default=RAIZ / "pruebas/resultados/contrato-recepcion-f04.json")
    opciones = argumentos.parse_args()
    version = subprocess.run(["git", "rev-parse", "HEAD"], cwd=RAIZ, capture_output=True, text=True)
    reporte = {"fechaUtc": datetime.now(timezone.utc).isoformat(), "commitVerificador": version.stdout.strip(),
        "entorno": "local desechable con Seguridad temporal", "aprobado": False, "casos": []}
    try:
        with entorno_local_con_comandos() as (gateway, gestor, sin_permiso, compose):
            ejecutar_contrato(compose, gateway, gestor, sin_permiso, reporte)
        reporte["aprobado"] = bool(reporte["casos"]) and all(caso["aprobado"] for caso in reporte["casos"])
    except RuntimeError as error:
        reporte["error"] = str(error)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError):
        reporte["error"] = "No se completó el contrato; revisar Docker, servicios y estructura de las respuestas"
    opciones.salida.parent.mkdir(parents=True, exist_ok=True)
    opciones.salida.write_text(json.dumps(reporte, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Resultado: " + ("APROBADO" if reporte["aprobado"] else "FALLIDO"))
    print("Informe: " + str(opciones.salida))
    return 0 if reporte["aprobado"] else 1


if __name__ == "__main__":
    sys.exit(main())
