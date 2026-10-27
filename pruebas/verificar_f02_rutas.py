#!/usr/bin/env python3
"""Recorrido real aislado. No carga .env ni utiliza bases/volúmenes del desarrollador."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

RAIZ = Path(__file__).resolve().parents[1]
FRONTEND = RAIZ.parent / "G2-Despacho-Frontend"
PROYECTO = "f02-pruebas-" + uuid.uuid4().hex[:12]
COMPOSE = ["docker", "compose", "--env-file", "/dev/null", "-p", PROYECTO,
           "-f", str(RAIZ / "pruebas/compose.f02.yaml")]


def ejecutar(*argumentos, **opciones):
    return subprocess.run(*argumentos, check=True, **opciones)


def solicitar(url, cuerpo=None, token=None, formulario=None, clave=None):
    cabeceras = {"Idempotency-Key": clave} if clave else {}
    datos = None
    if cuerpo is not None:
        datos = json.dumps(cuerpo).encode()
        cabeceras["Content-Type"] = "application/json"
    if formulario is not None:
        datos = urllib.parse.urlencode(formulario).encode()
        cabeceras["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        cabeceras["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, data=datos, headers=cabeceras), timeout=10) as respuesta:
        return json.load(respuesta)


def esperar(url):
    for _ in range(90):
        try:
            solicitar(url)
            return
        except (OSError, urllib.error.HTTPError):
            time.sleep(2)
    raise RuntimeError("El servicio de pruebas no inició: " + url)


def sql(base, sentencia):
    ejecutar(COMPOSE + ["exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "prueba", "-d", base],
             input=sentencia, text=True, stdout=subprocess.DEVNULL)


def verificar_anulacion_fallido(api, gestor, ventas, repartidor, jornada, zona):
    """Fallo canónico por API interna real; no simula la captura de evidencia de campo."""
    despacho = solicitar(api + "/despachos/simulaciones", {"idZona": zona, "pesoKg": 2, "volumenM3": 0.01}, gestor)
    identificador = despacho["idDespacho"]
    solicitar(api + "/despachos/" + identificador + "/asignacion", {"idRepartidor": repartidor, "idJornada": jornada}, gestor)
    interno = solicitar("http://127.0.0.1:18090/api/v1/auth/token", formulario={"grant_type": "client_credentials",
        "client_id": "modulo-despacho", "client_secret": "despacho-local-no-produccion", "scope": "gestion:despachos:transicionar"})["accessToken"]
    peticion = {"estadoDestino": "FALLIDO", "idRepartidor": repartidor, "idJornada": jornada,
        "motivo": "NO_INTENTADO", "comentario": "Preparación sintética para anulación", "ejecutor": repartidor, "origen": "MANUAL"}
    # No publicar la API interna mediante Gateway ni imprimir credenciales en los registros.
    resultado = subprocess.run(COMPOSE + ["exec", "-T", "seguridad-local", "curl", "--fail", "--silent", "--config", "-"],
        input='url = "http://gestion:8081/internal/v1/despachos/' + identificador + '/transiciones"\n'
        + 'header = "Authorization: Bearer ' + interno + '"\n'
        + 'header = "Idempotency-Key: ' + str(uuid.uuid4()) + '"\nheader = "Content-Type: application/json"\n'
        + 'data = ' + json.dumps(json.dumps(peticion)) + '\n', text=True, capture_output=True)
    if resultado.returncode != 0:
        raise RuntimeError("No se pudo preparar el fallo por la API interna real")
    assert json.loads(resultado.stdout)["estado"] == "FALLIDO"
    ruta = api + "/despachos/" + identificador
    detalle = solicitar(ruta + "/incidencia", token=gestor)
    clave = str(uuid.uuid4())
    cancelacion = solicitar(api + "/despachos/pedidos/" + despacho["idPedido"] + "/cancelacion",
        {"motivo": "PEDIDO_ANULADO"}, ventas, clave=clave)
    assert cancelacion["estado"] == "FALLIDO"
    actual = solicitar(ruta + "/incidencia", token=gestor)
    assert actual["pedidoAnulado"] and actual["versionDespacho"] == detalle["versionDespacho"] + 1
    solicitar(ruta + "/recepcion-centro", {"idIncidencia": actual["incidencia"]["idIncidencia"],
        "versionDespacho": actual["versionDespacho"], "selloIntacto": True}, gestor)
    # La recepción física, no la marca de anulación, solicita la liberación de la reserva del fallido.
    for _ in range(80):
        actual = solicitar(ruta + "/incidencia", token=gestor)
        if actual["incidencia"]["recepcion"]["liberacionCapacidad"]["estado"] == "CONFIRMADA":
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("No se confirmó la liberación posterior a recepción del fallido")
    assert not actual["acciones"]["reprogramar"]["habilitada"]
    manana = (datetime.now(ZoneInfo("America/Lima")) + timedelta(days=1)).date().isoformat()
    try:
        solicitar(ruta + "/reprogramacion", {"idIncidencia": actual["incidencia"]["idIncidencia"],
            "versionDespacho": actual["versionDespacho"], "nuevaFechaProgramada": manana}, gestor)
        raise AssertionError("Se permitió reprogramar un pedido anulado")
    except urllib.error.HTTPError as error:
        assert error.code == 409 and json.load(error)["code"] == "DESP_ERROR_PEDIDO_ANULADO"
    cierre = solicitar(ruta + "/devolucion-origen", {"idIncidencia": actual["incidencia"]["idIncidencia"],
        "versionDespacho": actual["versionDespacho"], "motivo": "Pedido anulado recibido en centro"}, gestor)
    assert cierre["estado"] == "DEVUELTO_A_ORIGEN"
    assert solicitar(api + "/despachos/pedidos/" + despacho["idPedido"] + "/cancelacion",
        {"motivo": "PEDIDO_ANULADO"}, ventas, clave=clave) == cancelacion
    print("F02_ANULACION_FALLIDO_VERIFICADO: marca, recepción, liberación F-06, bloqueo de reprogramación y cierre F-04 reales.")


def main():
    for servicio in ["servicio-gestion-despachos", "servicio-operacion-reparto", "api-gateway"]:
        if not (RAIZ / servicio / "target" / (servicio + "-0.0.1-SNAPSHOT.jar")).is_file():
            raise RuntimeError("Ejecuta ./mvnw clean verify en " + servicio)
    directorio = Path("/tmp/opencode")
    directorio.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="f02-", dir=directorio) as temporal:
        registro = Path(temporal) / "servicios.log"
        try:
            ejecutar(COMPOSE + ["up", "--build", "-d"], cwd=RAIZ)
            esperar("http://127.0.0.1:18090/actuator/health")
            esperar("http://127.0.0.1:18080/actuator/health")
            # El Gateway puede estar sano antes que sus proveedores terminen Flyway.
            for servicio, puerto in [("gestion", 8081), ("operacion", 8082)]:
                for _ in range(90):
                    resultado = subprocess.run(COMPOSE + ["exec", "-T", "seguridad-local", "curl", "--fail", "--silent",
                        f"http://{servicio}:{puerto}/actuator/health"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    if resultado.returncode == 0:
                        break
                    time.sleep(2)
                else:
                    raise RuntimeError("No inició " + servicio)
            gestor = solicitar("http://127.0.0.1:18090/pruebas/token-usuario", formulario={"perfil": "gestor"})["accessToken"]
            repartidor = solicitar("http://127.0.0.1:18090/pruebas/token-usuario", formulario={"perfil": "repartidor"})["accessToken"]
            # F-03 habilita por perfil vinculado, no por un rol global de repartidor.
            destino_token = solicitar("http://127.0.0.1:18090/pruebas/token-usuario", formulario={"perfil": "sin-rol"})["accessToken"]
            ventas = solicitar("http://127.0.0.1:18090/api/v1/auth/token", formulario={"grant_type": "client_credentials",
                "client_id": "modulo-ventas", "client_secret": "despacho-local-no-produccion", "scope": "despachos:cancelar"})["accessToken"]
            zona, distrito, perfil, vehiculo = (str(uuid.uuid4()) for _ in range(4))
            perfil_destino, vehiculo_destino = (str(uuid.uuid4()) for _ in range(2))
            actor = "11111111-1111-4111-8111-111111111111"
            sql("despacho_gestion", f"""
                INSERT INTO distrito(id,nombre) VALUES ('{distrito}','Distrito de pruebas F02');
                INSERT INTO zonas(id,nombre,estado) VALUES ('{zona}','Zona de pruebas F02','ACTIVO');
                INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('{zona}','{distrito}');
                INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('{zona}',10,1);
            """)
            sql("despacho_operacion", f"""
                INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
                    id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
                VALUES ('{perfil}','Ruta','Prueba','12345678','999111222','ruta@example.invalid','TEST-01','COMPLETO','ACTIVO',
                    '22222222-2222-4222-8222-222222222222','VINCULADO','{actor}','{actor}');
                INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
                VALUES ('{vehiculo}','F02-TEST','DISPONIBLE',500,4,80,'{actor}','{actor}');
                INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
                    id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
                VALUES ('{perfil_destino}','Destino','Prueba','87654321','999111223','destino@example.invalid','TEST-02','COMPLETO','ACTIVO',
                    '33333333-3333-4333-8333-333333333333','VINCULADO','{actor}','{actor}');
                INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
                VALUES ('{vehiculo_destino}','F02-DEST','DISPONIBLE',500,4,80,'{actor}','{actor}');
            """)
            api = "http://127.0.0.1:18080/api/v1"
            jornada = solicitar(api + "/jornadas", {"idRepartidor": perfil, "idFurgoneta": vehiculo, "idZona": zona,
                "fecha": datetime.now(ZoneInfo("America/Lima")).date().isoformat()}, gestor)["idJornada"]
            jornada_destino = solicitar(api + "/jornadas", {"idRepartidor": perfil_destino, "idFurgoneta": vehiculo_destino, "idZona": zona,
                "fecha": datetime.now(ZoneInfo("America/Lima")).date().isoformat()}, gestor)["idJornada"]
            base_destino = solicitar(api + "/despachos/simulaciones", {"idZona": zona, "pesoKg": 2, "volumenM3": 0.01}, gestor)
            solicitar(api + "/despachos/" + base_destino["idDespacho"] + "/asignacion",
                      {"idRepartidor": perfil_destino, "idJornada": jornada_destino}, gestor)
            for _ in range(2):
                despacho = solicitar(api + "/despachos/simulaciones", {"idZona": zona, "pesoKg": 2, "volumenM3": 0.01}, gestor)
                solicitar(api + "/despachos/" + despacho["idDespacho"] + "/asignacion",
                          {"idRepartidor": perfil, "idJornada": jornada}, gestor)
            entorno = dict(os.environ, F02_GESTOR_TOKEN=gestor, F02_REPARTIDOR_TOKEN=repartidor, F02_JORNADA=jornada,
                           F02_DESTINO_TOKEN=destino_token, F02_JORNADA_DESTINO=jornada_destino, F02_REPARTIDOR_ORIGEN=perfil, F02_ZONA=zona,
                           F02_VENTAS_TOKEN=ventas)
            ejecutar(["npm", "run", "test:f02:rutas:e2e"], cwd=FRONTEND, env=entorno)
            verificar_anulacion_fallido(api, gestor, ventas, perfil, jornada, zona)
            print("F02_RUTAS_VERIFICADO: navegador, Gateway, Gestión, Operación y consulta F-03 reales; Seguridad temporal.")
        except BaseException:
            with registro.open("w") as archivo:
                subprocess.run(COMPOSE + ["logs", "--no-color", "--tail", "100"], stdout=archivo, stderr=subprocess.STDOUT)
            # Solo registros del entorno sintético; nunca imprimir tokens.
            print(registro.read_text())
            raise
        finally:
            ejecutar(COMPOSE + ["down", "--remove-orphans"], cwd=RAIZ)


if __name__ == "__main__":
    main()
