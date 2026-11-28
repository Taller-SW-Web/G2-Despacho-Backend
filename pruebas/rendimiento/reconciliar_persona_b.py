#!/usr/bin/env python3
"""Lecturas posteriores de las bases desechables de Persona B; nunca durante la carga."""
import argparse
import json
import subprocess
from pathlib import Path


def sql(base, sentencia):
    comando = ["docker", "compose", "--env-file", "/dev/null", "-p", "hito5-persona-b",
               "-f", str(Path(__file__).resolve().parents[1] / "compose.f02.yaml"),
               "exec", "-T", "postgres", "psql", "-XAt", "-v", "ON_ERROR_STOP=1",
               "-U", "prueba", "-d", base]
    r = subprocess.run(comando, input=sentencia, text=True, capture_output=True, check=True)
    return json.loads(r.stdout.strip())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("caso", choices=["PERF-03", "PERF-04"])
    p.add_argument("directorio", type=Path)
    a = p.parse_args()
    aceptadas = [json.loads(l) for l in (a.directorio / "aceptadas.jsonl").read_text().splitlines()]
    if not aceptadas:
        raise AssertionError("No hay éxitos verificables")
    if a.caso == "PERF-03":
        datos = sql("despacho_gestion", """
            SELECT json_build_object('total',count(*),'coherentes',count(*) FILTER
            (WHERE peso_kg=2.65 AND volumen_m3=0.021 AND total=10 AND moneda='PEN' AND es_simulado))
            FROM cotizaciones;
            """)
        assert datos["total"] == datos["coherentes"] == len(aceptadas), (datos, len(aceptadas))
        limites = json.loads((a.directorio / "ventanas.json").read_text())["COTIZACION-CARGA"]
        inicio, fin = limites["inicioMs"], limites["inicioMs"] + limites["duracionS"] * 1000
        datos["medidasPersistidas"] = sql("despacho_gestion",
            f"SELECT to_json(count(*)) FROM cotizaciones WHERE creado_en >= to_timestamp({inicio}/1000.0) AND creado_en < to_timestamp({fin}/1000.0);")
        medidas = sum(f["fase"] == "COTIZACION-CARGA" for f in aceptadas)
        assert datos["medidasPersistidas"] == medidas, (datos, medidas)
    else:
        respuestas = {f["respuesta"]["idDespacho"]: f["respuesta"] for f in aceptadas}
        assert len(respuestas) == len(aceptadas), "Despacho repetido"
        reservadas = sql("despacho_operacion", """
            SELECT coalesce(json_agg(json_build_object('idDespacho',despacho_id_externo,
                'idReserva',id,'idJornada',asignacion_diaria_id)),'[]'::json)
            FROM reservas_capacidad WHERE estado='ACTIVA';
            """)
        assert len(reservadas) == len(respuestas), "Cantidad de reservas inesperada"
        for reserva in reservadas:
            r = respuestas[reserva["idDespacho"]]
            assert r["idReserva"] == reserva["idReserva"] and r["idJornada"] == reserva["idJornada"]
        asignadas = sql("despacho_gestion", """
            SELECT coalesce(json_agg(json_build_object('idDespacho',id,'idReserva',reserva_capacidad_id_externa,
                'idRepartidor',repartidor_id_externo,'idJornada',asignacion_diaria_id_externa)),'[]'::json)
            FROM despachos WHERE estado='ASIGNADO';
            """)
        assert len(asignadas) == len(respuestas)
        for d in asignadas:
            for campo in ("idReserva", "idRepartidor", "idJornada"):
                assert d[campo] == respuestas[d["idDespacho"]][campo]
        # Cada jornada del fixture posee límites 500 kg, 4 m3 y 80 paquetes.
        excesos = sql("despacho_operacion", """
            SELECT to_json(count(*)) FROM (SELECT asignacion_diaria_id FROM reservas_capacidad
            WHERE estado='ACTIVA' GROUP BY asignacion_diaria_id
            HAVING sum(peso_kg)>500 OR sum(volumen_m3)>4 OR sum(cantidad_paquetes)>80) e;
            """)
        assert excesos == 0, "Sobrecarga"
        datos = {"despachos": len(asignadas), "reservasActivas": len(reservadas), "jornadasExcedidas": excesos}
    (a.directorio / "reconciliacion.json").write_text(json.dumps(
        {"caso": a.caso, "resultado": "Aprobado", "datos": datos}, indent=2))


if __name__ == "__main__":
    main()
