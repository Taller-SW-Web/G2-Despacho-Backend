"""El informe contractual debe rechazar duplicados y liberaciones incompletas."""
from copy import deepcopy
import unittest

from verificar_contrato_recepcion_f04 import IncumplimientoContrato, validar_problema, verificar_persistencia


class ProblemaRecepcionTests(unittest.TestCase):
    def setUp(self):
        self.problema = {"status": 401, "code": "DESP_ERROR_TOKEN_INVALIDO", "title": "Token no aceptado",
            "detail": "No fue posible autenticar la solicitud", "instance": "/api/v1/despachos/prueba/recepcion-centro",
            "correlationId": "correlacion-sintetica", "timestamp": "2026-11-05T15:00:00Z"}

    def test_admite_tipo_generico_implicito_y_tipo_explicito(self):
        validar_problema(self.problema, 401)
        validar_problema(dict(self.problema, type="about:blank"), 401)

    def test_rechaza_estado_codigo_o_correlacion_incorrectos(self):
        for cambio in ({"status": 200}, {"code": ""}, {"correlationId": None}, {"type": []}):
            with self.subTest(cambio=cambio), self.assertRaises(IncumplimientoContrato):
                validar_problema(dict(self.problema, **cambio), 401)

    def test_rechaza_datos_protegidos_en_el_error(self):
        with self.assertRaises(IncumplimientoContrato):
            validar_problema(dict(self.problema, recepcion={"idUsuario": "ajeno"}), 401)


class PersistenciaRecepcionTests(unittest.TestCase):
    def setUp(self):
        self.antes = {"gestion": {"estado": "FALLIDO", "version": 3, "intentos": 0,
            "recepciones": 0, "historial": 0, "solicitudes": 0, "eventos": 0},
            "reservas": [{"id": "reserva-original", "estado": "ACTIVA", "liberadoEn": None}],
            "ocupacion": {"pesoKg": 2, "volumenM3": 0.01, "paquetes": 1}}
        self.despues = deepcopy(self.antes)
        self.despues["gestion"].update(version=4, recepciones=1, historial=1, solicitudes=1)
        self.despues["reservas"][0].update(estado="LIBERADA", liberadoEn="2026-11-05T15:00:00Z")
        self.despues["ocupacion"] = {"pesoKg": 0, "volumenM3": 0, "paquetes": 0}

    def test_acepta_una_recepcion_y_su_reserva_original_liberada(self):
        verificar_persistencia(self.antes, self.despues)

    def test_rechaza_recepcion_historial_o_solicitud_duplicados(self):
        for campo in ("recepciones", "historial", "solicitudes"):
            with self.subTest(campo=campo):
                alterada = deepcopy(self.despues)
                alterada["gestion"][campo] = 2
                with self.assertRaises(IncumplimientoContrato):
                    verificar_persistencia(self.antes, alterada)

    def test_rechaza_eventos_a_ventas_intentos_o_version_adicionales(self):
        for campo in ("eventos", "intentos", "version"):
            with self.subTest(campo=campo):
                alterada = deepcopy(self.despues)
                alterada["gestion"][campo] += 1
                with self.assertRaises(IncumplimientoContrato):
                    verificar_persistencia(self.antes, alterada)

    def test_rechaza_liberacion_de_otra_reserva(self):
        self.despues["reservas"][0]["id"] = "reserva-de-otra-salida"
        with self.assertRaises(IncumplimientoContrato):
            verificar_persistencia(self.antes, self.despues)

    def test_rechaza_capacidad_que_sigue_ocupada(self):
        self.despues["ocupacion"] = self.antes["ocupacion"]
        with self.assertRaises(IncumplimientoContrato):
            verificar_persistencia(self.antes, self.despues)

    def test_rechaza_reserva_aun_activa(self):
        self.despues["reservas"][0]["estado"] = "ACTIVA"
        with self.assertRaises(IncumplimientoContrato):
            verificar_persistencia(self.antes, self.despues)


if __name__ == "__main__":
    unittest.main()
