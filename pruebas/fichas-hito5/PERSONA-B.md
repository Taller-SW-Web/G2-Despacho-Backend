# Persona B · ejecución del hito 5

## Alcance y aislamiento

La rama `test/hito5-persona-b` parte de `test/hito5-integracion`, que combina `develop`
con A (`03bec4e`) y corrige sus herramientas. No se publica ni integra automáticamente en `develop`.
Se conserva Java 21 como exige el plan; las fichas históricas de A sobre Java 25 no se reescriben.

INT-01 usa `RecepcionLiberacionF04Test`: escenario aislado con una reserva, 14 comprobaciones,
cabeceras contractuales y comparación de persistencia. No reutiliza las dos reservas de la jornada
de INT-02. Los informes de Surefire son la autoridad sobre fallos; el JSON aporta lecturas.

## Comandos locales

Primero ejecutar `bash ./mvnw clean verify` con Java 21 en cada uno de los tres servicios.
Después, desde `pruebas/integracion-java`:

```bash
bash ./mvnw clean test -Dcommit="$(git rev-parse HEAD)"
# Comparación Python: desde la raíz backend, después de retirar el entorno Java:
python3 -B pruebas/verificar_contrato_recepcion_f04.py
```

Para rendimiento, preparar un solo caso por entorno nuevo:

```bash
# Desde pruebas/integracion-java:
bash ./mvnw test -Dtest=PreparacionPersonaB -Dcaso=PERF-03 -Dcommit="$(git rev-parse HEAD)"
# Desde pruebas/rendimiento, con árbol limpio:
JAVA_HOME=/ruta/jdk21 JMETER=/ruta/jmeter PROYECTO=hito5-persona-b bash ejecutar.sh PERF-03
```

Repetir con `PERF-04` solo después de retirar **exclusivamente** el proyecto
`hito5-persona-b` con `pruebas/compose.f02.yaml`. El proyecto es desechable y no usa `.env`.
El perfil local `productos-simulados` es explícito; no acredita G6.
PERF-04 genera 800 pendientes entre 20 jornadas, CSV no reciclable, 5 hilos y 2/s.
PERF-03 usa 5 hilos y 0,5/s. Ambos separan 60 s de calentamiento y 300 s medidos.
La reconciliación se ejecuta después; si falla, el ejecutor devuelve error.
No superponer con A: los puertos son fijos.

## Evidencia

El ejecutor requiere árbol limpio, registra commit y hashes de JAR antes de medir,
guarda ventanas nominales y empaqueta JTL, HTML completo, recursos, ledger saneado,
reconciliación y resúmenes en `resultados/<corrida>.tar.gz`. Ese paquete permanece fuera de Git:
debe adjuntarse al PR o al almacenamiento autorizado del equipo para revisión cruzada.
Las fichas de A siguen siendo evidencia histórica, no resultados recertificados con este analizador.

## INT-10 · Bloqueado

No se recibieron un origen QA autorizado de G6, credencial/grant real de G7,
confirmación vigente de los tres SKU ni acceso a evidencia correlacionada de emisión/uso del token.
No se consultarán proveedores externos con credenciales locales ni se sustituirá esta aceptación
por dobles. Solicitar esas dependencias al equipo antes de ejecutar INT-10.

## Cierre local

- INT-01 Java/Python convergente, rendimiento de B y revalidación de A ejecutados.
- INT-09 corregido de forma portable mediante intermediario Java dentro de Compose y verificado.
- Resultados completos y referencias de artefactos en [CIERRE-HITO5](./CIERRE-HITO5.md).
- Siguen pendientes INT-10 real, revisión humana cruzada, publicación de paquetes y PR a `develop`.

## Registro de esta ejecución · 27/11/2026

| Comprobación | Resultado observado |
|---|---|
| `clean verify` Gestión / Operación / Gateway con Corretto 21 | 321 / 149 / 40 pruebas aprobadas; cero omitidas |
| Regresiones del analizador y XML JMeter | 8 pruebas aprobadas |
| Compilación del módulo Java | Aprobada |
| Groovy de B y carga de `PERF-B.jmx` en JMeter 5.6.3 | Aprobadas; no equivale a una corrida de carga |
| INT-01 por Gateway, Gestión y Operación locales | 14 pruebas aprobadas |
| INT-02 e INT-08 | 7 pruebas aprobadas |
| INT-09 Linux | Primera corrida falló durante preparación; se corrigió el aislamiento de red. Corrida final aprobada y suite conjunta 22/22 |
| PERF-03 | 150 muestras, p95 30 ms, 0 errores; 150 instantáneas coherentes, conteos 31→181 |
| PERF-04 | 600 muestras, p95 29 ms, 0 errores; 721 asignados y reservas únicas contando control/calentamiento, sin sobrecarga |
| Comparación Python de INT-01 | 14/14 sobre datos recreados, convergente con Java |

Logs de sesión bajo `/tmp/opencode/hito5-*.log`; reportes y JSON de integración bajo
`pruebas/integracion-java/target/`. No se modificaron entornos compartidos. La ejecución local
no acredita G6/G7 reales, RabbitMQ habilitado ni aceptación humana.
