#!/usr/bin/env bash
# Corrida formal de un caso en modo CLI: JTL, informe HTML, CPU/RAM de los contenedores y análisis.
# Uso: JMETER=/ruta/jmeter ./ejecutar.sh PERF-05 [-Jpropiedad=valor ...]
set -euo pipefail
cd "$(dirname "$0")"

caso="${1:?Indica el caso: PERF-05 o PERF-06}"
shift
jmeter="${JMETER:-jmeter}"
proyecto="${PROYECTO:-hito5-perf}"
salida="resultados/${caso}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$salida"

case "$caso" in
  PERF-05) fases=(--fase RUTA-CARGA:3:500) ;;
  PERF-06) fases=(--fase RUTA-BASE:3:500 --fase RUTA-PICO:10:1000 --fase RUTA-RECUPERACION:3
                  --fase RUTA-RECUPERACION@ultimos30:3:500) ;;
  *) echo "Caso desconocido: $caso" >&2; exit 2 ;;
esac

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
  "-Jjmeter.reportgenerator.sample_filter=^RUTA-(BASE|PICO|RECUPERACION|CARGA)$" "$@"

kill $monitor 2>/dev/null || true
git rev-parse --short HEAD > "$salida/commit.txt" 2>/dev/null || true
"$jmeter" -v 2>/dev/null | grep -o '5\.[0-9.]*' | head -1 > "$salida/version-jmeter.txt" || true

python -I analizar_jtl.py "$salida/${caso}.jtl" "${fases[@]}" --salida "$salida/resumen.json" \
  | tee "$salida/resumen.txt" || true
echo "Evidencia en $salida"
