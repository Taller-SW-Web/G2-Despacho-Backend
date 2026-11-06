"""Regresiones del verificador: una respuesta incorrecta nunca da aprobación."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from verificar_humo_f04 import comprobar, main


class VerificarHumoTests(unittest.TestCase):
    def setUp(self):
        self.alteracion = None
        self.recibidos = []
        pruebas = self

        class Receptor(BaseHTTPRequestHandler):
            def log_message(self, formato, *args):
                pass

            def do_GET(self):
                token = self.headers.get("Authorization")
                pruebas.recibidos.append((self.path, token))
                tipo = "application/json"
                if self.path == "/actuator/health":
                    estado, datos = 200, {"status": "UP"}
                elif token == "Bearer gestor-sintetico":
                    estado, datos = 200, {"contenido": [], "totalElementos": 0, "pagina": 1, "tamanoPagina": 1}
                    if pruebas.alteracion == "cuerpo":
                        datos = {"mensaje": "OK"}
                else:
                    estado = 403 if token else 401
                    tipo = "application/problem+json"
                    datos = {"status": estado, "code": "DESP_ERROR_TOKEN_INVALIDO"}
                    if pruebas.alteracion == "abierto":
                        estado = 200
                    elif pruebas.alteracion == "filtracion":
                        datos["contenido"] = [{"idDespacho": "dato-protegido"}]
                    elif pruebas.alteracion == "redireccion":
                        self.send_response(302)
                        self.send_header("Location", "/destino-redireccion")
                        self.end_headers()
                        return
                contenido = json.dumps(datos).encode()
                self.send_response(estado)
                self.send_header("Content-Type", tipo)
                self.end_headers()
                self.wfile.write(contenido)

        self.servidor = ThreadingHTTPServer(("127.0.0.1", 0), Receptor)
        self.hilo = threading.Thread(target=self.servidor.serve_forever, daemon=True)
        self.hilo.start()
        self.origen = "http://127.0.0.1:" + str(self.servidor.server_port)

    def tearDown(self):
        self.servidor.shutdown()
        self.servidor.server_close()
        self.hilo.join()

    def ejecutar(self):
        return comprobar(self.origen, "gestor-sintetico", "sin-permiso-sintetico")

    def test_aprueba_y_envia_cada_identidad_sin_incluirla_en_el_informe(self):
        casos = self.ejecutar()
        self.assertTrue(all(caso["aprobado"] for caso in casos), casos)
        self.assertEqual([token for _, token in self.recibidos],
                         [None, None, "Bearer sin-permiso-sintetico", "Bearer gestor-sintetico"])
        self.assertNotIn("sintetico", json.dumps(casos))

    def test_falla_si_acepta_acceso_anonimo(self):
        self.alteracion = "abierto"
        caso = self.ejecutar()[1]
        self.assertEqual(caso["obtenido"], 200)
        self.assertFalse(caso["aprobado"])

    def test_falla_si_el_200_no_contiene_el_listado(self):
        self.alteracion = "cuerpo"
        caso = self.ejecutar()[3]
        self.assertEqual(caso["obtenido"], 200)
        self.assertFalse(caso["aprobado"])

    def test_falla_si_un_rechazo_filtra_contenido(self):
        self.alteracion = "filtracion"
        casos = self.ejecutar()
        self.assertEqual(casos[1]["obtenido"], 401)
        self.assertFalse(casos[1]["aprobado"])
        self.assertNotIn("dato-protegido", json.dumps(casos))

    def test_rechaza_redireccion_sin_reenviar_el_token(self):
        self.alteracion = "redireccion"
        caso = self.ejecutar()[2]
        self.assertEqual(caso["obtenido"], 302)
        self.assertFalse(caso["aprobado"])
        self.assertNotIn("/destino-redireccion", [ruta for ruta, _ in self.recibidos])

    def test_comando_fallido_devuelve_uno_y_guarda_un_reporte_sin_tokens(self):
        self.alteracion = "abierto"
        with tempfile.TemporaryDirectory() as carpeta:
            salida = Path(carpeta) / "informe.json"
            with patch.dict("os.environ", {"HUMO_TOKEN_GESTOR": "gestor-sintetico",
                    "HUMO_TOKEN_SIN_PERMISO": "sin-permiso-sintetico"}), redirect_stdout(io.StringIO()):
                with patch("sys.argv", ["verificar_humo_f04.py", "--gateway-url", self.origen,
                        "--salida", str(salida)]):
                    self.assertEqual(main(), 1)
            contenido = salida.read_text(encoding="utf-8")
            self.assertFalse(json.loads(contenido)["aprobado"])
            self.assertNotIn("sintetico", contenido)


if __name__ == "__main__":
    unittest.main()
