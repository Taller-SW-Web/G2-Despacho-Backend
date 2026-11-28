import unittest
from pathlib import Path
import xml.etree.ElementTree as ET


class PlanesTests(unittest.TestCase):
    def test_csv_de_asignacion_no_aplica_al_setup(self):
        raiz = ET.parse(Path(__file__).with_name("PERF-B.jmx")).getroot()
        grupos = raiz.findall(".//SetupThreadGroup")
        self.assertEqual(1, len(grupos))
        self.assertEqual([], grupos[0].findall(".//CSVDataSet"))
        csv = raiz.find(".//CSVDataSet")
        self.assertEqual("${__P(reciclar,false)}", csv.find("./stringProp[@name='recycle']").text)
        self.assertEqual("shareMode.all", csv.find("./stringProp[@name='shareMode']").text)

    def test_ningun_plan_sigue_redirecciones(self):
        for archivo in Path(__file__).parent.glob("*.jmx"):
            for sampler in ET.parse(archivo).findall(".//HTTPSamplerProxy"):
                self.assertEqual("false", sampler.find("./boolProp[@name='HTTPSampler.follow_redirects']").text)


if __name__ == "__main__":
    unittest.main()
