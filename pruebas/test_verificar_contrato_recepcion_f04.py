"""El informe contractual debe rechazar duplicados y liberaciones incompletas."""
from copy import deepcopy
import unittest

from verificar_contrato_recepcion_f04 import IncumplimientoContrato, verificar_persistencia


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
