"""Regresión HTTP local del consultor periódico; no llama a Render."""
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import unittest

from herramientas.mantener_backends_activos import consultar, segundos_retry_after


class RespuestasSalud(BaseHTTPRequestHandler):
    llegadas = []

    def do_GET(self):
        self.llegadas.append((self.path, self.headers.get("Authorization")))
        if self.path == "/conexion-cerrada":
            self.close_connection = True
            return
        estado, cuerpo = 200, b'{"status":"UP"}'
        if self.path == "/limite":
            estado, cuerpo = 429, b'{}'
        elif self.path == "/arrancando":
            cuerpo = b'<html>Starting...</html>'
        elif self.path == "/redireccion":
            estado, cuerpo = 302, b''
        self.send_response(estado)
        if estado == 429:
            self.send_header("Retry-After", "600")
        if estado == 302:
            self.send_header("Location", "/destino-redirigido")
        self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        self.wfile.write(cuerpo)

    def log_message(self, *args):
        pass


class MantenerBackendsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.servidor = ThreadingHTTPServer(("127.0.0.1", 0), RespuestasSalud)
        cls.hilo = threading.Thread(target=cls.servidor.serve_forever, daemon=True)
        cls.hilo.start()
        cls.origen = f"http://127.0.0.1:{cls.servidor.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.servidor.shutdown()
        cls.servidor.server_close()
        cls.hilo.join()

    def test_confirma_salud_sin_enviar_credenciales(self):
        correcto, detalle, _, _ = consultar(self.origen + "/salud", 2)
        self.assertTrue(correcto)
        self.assertIn("salud UP", detalle)
        self.assertEqual(RespuestasSalud.llegadas[-1], ("/salud", None))

    def test_devuelve_pausa_indicada_ante_429(self):
        correcto, detalle, espera, _ = consultar(self.origen + "/limite", 2)
        self.assertFalse(correcto)
        self.assertIn("429", detalle)
        self.assertEqual(espera, 600)

    def test_no_confunde_pagina_de_arranque_con_salud_up(self):
        self.assertFalse(consultar(self.origen + "/arrancando", 2)[0])

    def test_no_sigue_redirecciones(self):
        inicio = len(RespuestasSalud.llegadas)
        correcto, detalle, _, _ = consultar(self.origen + "/redireccion", 2)
        self.assertFalse(correcto)
        self.assertIn("302", detalle)
        self.assertEqual(RespuestasSalud.llegadas[inicio:], [("/redireccion", None)])

    def test_registra_fallo_de_red_y_permite_consultas_posteriores(self):
        correcto, detalle, _, _ = consultar(self.origen + "/conexion-cerrada", 2)
        self.assertFalse(correcto)
        self.assertIn("sin respuesta", detalle)
        self.assertTrue(consultar(self.origen + "/salud", 2)[0])

    def test_retry_after_admite_fecha_http_e_ignora_valores_invalidos(self):
        fecha = datetime.now(timezone.utc) + timedelta(minutes=10)
        espera = segundos_retry_after(format_datetime(fecha, usegmt=True))
        self.assertGreater(espera, 590)
        self.assertLessEqual(espera, 600)
        self.assertEqual(segundos_retry_after("inválido"), 0)


if __name__ == "__main__":
    unittest.main()
