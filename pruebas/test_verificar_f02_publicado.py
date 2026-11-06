"""Dobles explícitos del verificador; no acreditan un despliegue real."""
import copy
import json
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
from verificar_f02_publicado import solicitar, validar_origen, verificar


class VerificadorTests(unittest.TestCase):
    def setUp(self):
        self.respuestas = {
            ("/actuator/health", None): (200, {"status": "UP"}),
            ("/api/v1/despachos/pendientes", None): (401, {}),
            ("/api/v1/repartidor/mi-ruta", None): (401, {}),
            ("/api/v1/despachos/pendientes", "gestor"): (200, {"contenido": [], "indicadores": {}, "fechaActualServidor": "2026-11-05"}),
            ("/api/v1/jornadas/jornada/ruta", "gestor"): (200, {"idJornada": "jornada", "estadoJornada": "ACTIVA", "versionRuta": "version",
                "despachos": [{"idDespacho": "despacho", "estado": "ASIGNADO", "posicion": 1, "idRepartidor": "perfil",
                               "pesoKg": 2, "volumenM3": 0.01, "cantidadPaquetes": 1}]}),
            ("/api/v1/repartidor/mi-ruta", "dueño"): (200, {"idJornada": "jornada", "despachos": [
                {"idDespacho": "despacho", "estado": "ASIGNADO", "secuenciaRuta": 1}]}),
            ("/api/v1/jornadas/jornada", "gestor"): (200, {"idJornada": "jornada", "idRepartidor": "perfil", "estado": "ACTIVA",
                "limites": {"pesoKg": 20, "volumenM3": 1, "paquetes": 10},
                "ocupacion": {"pesoKg": 2, "volumenM3": 0.01, "paquetes": 1},
                "capacidadRemanente": {"pesoKg": 18, "volumenM3": 0.99, "paquetes": 9}}),
            ("/api/v1/repartidor/despachos/despacho", "otro"): (404, {}),
        }

    def ejecutar(self):
        return verificar("https://gateway.example", "gestor", "dueño", "otro", "despacho", "jornada",
                         lambda origen, ruta, token: self.respuestas[(ruta, token)])

    def test_recorrido_exitoso_sin_conservar_tokens_ni_datos_personales(self):
        pasos = self.ejecutar()
        self.assertTrue(all(p["correcto"] for p in pasos))
        self.assertEqual(len(pasos), 9)
        self.assertNotIn("dueño", json.dumps(pasos))

    def test_detecta_ausencia_duplicado_estado_y_secuencia_distinta(self):
        clave = ("/api/v1/repartidor/mi-ruta", "dueño")
        original = copy.deepcopy(self.respuestas[clave][1])
        for paradas in ([], original["despachos"] * 2,
                        [{"idDespacho": "despacho", "estado": "EN_CAMINO", "secuenciaRuta": 1}],
                        [{"idDespacho": "despacho", "estado": "ASIGNADO", "secuenciaRuta": 2}]):
            with self.subTest(paradas=paradas):
                self.respuestas[clave] = (200, {**original, "despachos": paradas})
                self.assertFalse(all(p["correcto"] for p in self.ejecutar()))

    def test_no_confunde_html_o_dependencia_caida_con_exito(self):
        for cuerpo in (None, {}, {"status": "DOWN"}):
            self.respuestas[("/actuator/health", None)] = (200, cuerpo)
            self.assertFalse(self.ejecutar()[0]["correcto"])

    def test_rechaza_exposicion_anonima_o_ajena_y_errores_de_transporte(self):
        for clave in (("/api/v1/despachos/pendientes", None), ("/api/v1/repartidor/despachos/despacho", "otro")):
            anterior = self.respuestas[clave]
            self.respuestas[clave] = (200, {})
            self.assertFalse(all(p["correcto"] for p in self.ejecutar()))
            self.respuestas[clave] = anterior
        self.respuestas.clear()
        self.assertTrue(all(not p["correcto"] for p in self.ejecutar()))

    def test_capacidad_no_puede_ser_negativa_incoherente_o_no_reservada(self):
        datos = self.respuestas[("/api/v1/jornadas/jornada", "gestor")][1]
        for carga in (-1, 0, 99, "2"):
            datos["ocupacion"]["pesoKg"] = carga
            self.assertFalse(all(p["correcto"] for p in self.ejecutar()))

    def test_origen_seguro_sin_credenciales_rutas_o_http_remoto(self):
        for origen in ("https://u:clave@example.com", "http://example.com", "https://example.com/api", "https://example.com?token=x", "file:///tmp/x"):
            with self.assertRaises(ValueError):
                validar_origen(origen)
        self.assertEqual(validar_origen("http://127.0.0.1:8080/"), "http://127.0.0.1:8080")

    def test_solicitar_no_sigue_redireccion_ni_filtra_bearer(self):
        recibidas = []
        class Servidor(BaseHTTPRequestHandler):
            def do_GET(self):
                recibidas.append(self.path)
                self.send_response(302)
                self.send_header("Location", "/destino")
                self.end_headers()
            def log_message(self, *args):
                pass
        with HTTPServer(("127.0.0.1", 0), Servidor) as servidor:
            hilo = Thread(target=servidor.serve_forever, daemon=True)
            hilo.start()
            try:
                self.assertEqual(solicitar(f"http://127.0.0.1:{servidor.server_port}", "/origen", "secreto")[0], 302)
                self.assertEqual(recibidas, ["/origen"])
            finally:
                servidor.shutdown()
                hilo.join()


if __name__ == "__main__":
    unittest.main()
