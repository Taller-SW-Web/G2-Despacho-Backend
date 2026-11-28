#!/usr/bin/env bash
# Corrida formal de un caso en modo CLI: JTL, informe HTML, CPU/RAM de los contenedores y análisis.
# Uso: JMETER=/ruta/jmeter ./ejecutar.sh PERF-05 [-Jpropiedad=valor ...]
set -euo pipefail
cd "$(dirname "$0")"

caso="${1:?Indica el caso: PERF-02, PERF-05 o PERF-06}"
shift
jmeter="${JMETER:-jmeter}"
proyecto="${PROYECTO:-hito5-perf}"
salida="resultados/${caso}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$salida"

case "$caso" in
  PERF-02) fases=(--fase COLA-CARGA:5:250) ;;
  PERF-05) fases=(--fase RUTA-CARGA:3:500) ;;
  PERF-06) fases=(--fase RUTA-BASE:3:500 --fase RUTA-PICO:10:1000 --fase RUTA-RECUPERACION:3
                  --fase RUTA-RECUPERACION@ultimos30:3:500) ;;
  PERF-03) fases=(--fase COTIZACION-CARGA:0.5:100) ;;
  PERF-04) fases=(--fase ASIGNACION-CARGA:2:1000) ;;
  *) echo "Caso desconocido: $caso" >&2; exit 2 ;;
esac

# La evidencia corresponde a un commit y artefactos concretos, no al commit posterior a medir.
if [[ -n "$(git status --porcelain)" ]]; then
  echo "La corrida formal requiere un árbol Git limpio; guarda primero el código." >&2
  exit 2
fi
git rev-parse HEAD > "$salida/commit.txt"
sha256sum ../../*/target/*-SNAPSHOT.jar > "$salida/jars.sha256"

# CPU y RAM de Gateway, Gestión, Operación y PostgreSQL cada 5 s, fuera de JMeter.
echo "instante,contenedor,cpu,memoria" > "$salida/recursos.csv"
(
  while true; do
    instante=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
      | grep "^${proyecto}-" | sed "s/^/${instante},/" >> "$salida/recursos.csv" || true
    sleep 5
  done
) &
monitor=$!
trap 'kill $monitor 2>/dev/null || true' EXIT

# Solo las fases medidas entran al informe HTML; el calentamiento y las sesiones quedan en el JTL.
"$jmeter" -n -t "${caso}.jmx" -l "$salida/${caso}.jtl" -j "$salida/jmeter.log" \
  -e -o "$salida/html" \
  "-Jjmeter.reportgenerator.sample_filter=^(RUTA-(BASE|PICO|RECUPERACION|CARGA)|COLA-CARGA|COTIZACION-CARGA|ASIGNACION-CARGA)$" \
  "-Jventanas=$PWD/$salida/ventanas.json" "$@"

kill $monitor 2>/dev/null || true
git rev-parse --short HEAD > "$salida/commit.txt" 2>/dev/null || true
"$jmeter" -v 2>/dev/null | grep -o '5\.[0-9.]*' | head -1 > "$salida/version-jmeter.txt" || true

estado=0
python3 -I analizar_jtl.py "$salida/${caso}.jtl" "${fases[@]}" \
  --ventanas "$salida/ventanas.json" --salida "$salida/resumen.json" \
  | tee "$salida/resumen.txt" || estado=$?
tar -czf "$salida.tar.gz" "$salida"
echo "Evidencia en $salida"
exit "$estado"
