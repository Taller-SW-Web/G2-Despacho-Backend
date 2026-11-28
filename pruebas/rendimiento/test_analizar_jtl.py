import unittest
from analizar_jtl import resumir


def muestra(instante, codigo="200", exito="true"):
    return {"timeStamp": str(instante), "elapsed": "10", "responseCode": codigo,
            "success": exito}


class AnalizadorTests(unittest.TestCase):
    def test_una_muestra_no_acredita_cinco_minutos(self):
        self.assertEqual("Fallido", resumir([muestra(0)], .5, 100,
                                           inicio_ms=0, duracion_s=300)["resultado"])

    def test_sin_ventana_no_se_infiere_aprobacion(self):
        self.assertEqual("No concluyente", resumir([muestra(0)], .5, 100)["resultado"])

    def test_carga_completa_usa_duracion_nominal(self):
        r = resumir([muestra(i * 2000) for i in range(150)], .5, 100,
                    inicio_ms=0, duracion_s=300)
        self.assertEqual("Aprobado", r["resultado"])
        self.assertEqual(.5, r["tasaLograda"])

    def test_recuperacion_truncada_no_desplaza_ultimos30(self):
        r = resumir([muestra(i * 333) for i in range(90)], 3, 500, ventana=30,
                    inicio_ms=0, duracion_s=60)
        self.assertEqual("No concluyente", r["resultado"])

    def test_un_fallo_de_negocio_invalida_la_carga(self):
        filas = [muestra(i * 2000) for i in range(150)]
        filas[0]["success"] = "false"
        self.assertEqual("Fallido", resumir(filas, .5, 100, inicio_ms=0,
                                           duracion_s=300)["resultado"])


if __name__ == "__main__":
    unittest.main()
