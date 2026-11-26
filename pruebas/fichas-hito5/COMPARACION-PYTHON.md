# Comparación Java ↔ Python · INT-02 e INT-08

El plan pide ejecutar el verificador Java y el Python sobre datos recreados, comprobar que sus aserciones convergen y hacerlo antes de retirar o sustituir los recorridos vigentes.

| | Python | Java |
|---|---|---|
| **Verificador** | `pruebas/verificar_f02_rutas.py`, que usa `verificar()` de `verificar_f02_publicado.py` | `RutasRepartidorF02Test` (`pruebas/integracion-java`) |
| **Commit** | `47a8c63` | `47a8c63` |
| **Fecha** | 2026-11-26, terminó a las 09:11:45 UTC | 2026-11-26, de 09:11:57 a 09:12:29 UTC |
| **Ejecución** | WSL Ubuntu (Python 3.12), con la integración de Docker Desktop | Windows, JDK 25.0.5.1 |
| **Entorno** | `compose.f02.yaml`, proyecto `f02-pruebas-<id>`, bases desechables | `compose.f02.yaml`, proyecto `hito5-it-<id>`, bases desechables |
| **Datos** | Recreados por el propio script | Recreados por `EscenarioRutasF02` (migración del mismo escenario) |
| **Resultado** | `F02_RUTAS_VERIFICADO`: los 9 pasos correctos | 7 tests, 27 aserciones correctas (INT-02: 18 · INT-08: 9) |

## Convergencia por comprobación

| Paso Python | Resultado Python | Equivalente Java | Resultado Java |
|---|---|---|---|
| `salud_gateway` | correcto | `salud_gateway` | correcto |
| `cola_sin_token` (401) | correcto | `cola_sin_token_401` | correcto |
| `ruta_sin_token` (401) | correcto | `ruta_sin_token_401` | correcto |
| `cola_autorizada` | correcto | `cola_autorizada_estructura` | correcto |
| `ruta_gestion` | correcto | `ruta_gestion_estructura` | correcto |
| `ruta_propia` | correcto | `mi_ruta_jornada_propia` | correcto |
| `ocupacion_jornada` | correcto | `ocupacion_jornada_estructura` | correcto |
| `mismo_despacho_jornada_secuencia_y_ocupacion` | correcto | `aparece_una_vez_en_cada_ruta`, `estado_asignado_en_ambas`, `misma_secuencia`, `mismo_repartidor_de_la_jornada`, `capacidad_*` | correcto |
| `despacho_ajeno` (404) | correcto | `despacho_ajeno_404` | correcto |

Las 9 comprobaciones de Python tienen su equivalente en Java y **coinciden: todas correctas en ambos**.

Java agrega aserciones que Python no hacía:

- **INT-02:** el despacho asignado ya no está en la cola, y la ocupación es igual a la suma de la ruta.
- **INT-08:** el cuerpo del `404` no contiene datos del despacho, hay dos controles de identidad, y un ID ajeno responde igual que uno inexistente.

El script Python también ejecuta `verificar_anulacion_fallido`, que imprimió `F02_ANULACION_FALLIDO_VERIFICADO`. Ese recorrido de F-04 queda fuera del alcance de INT-02 e INT-08 y no se migró. **Por eso el script Python no debe retirarse**: sigue siendo la única cobertura de ese recorrido.

**Conclusión.** La suite Java reproduce el comportamiento verificado por Python para INT-02 e INT-08 y lo amplía. Puede sustituir esas comprobaciones; el script Python se conserva por `verificar_anulacion_fallido`.

Evidencia Java de esta corrida: [`int-02-int-08-comparacion.json`](evidencia/int-02-int-08-comparacion.json).

El script Python no deja informe cuando todo pasa: solo imprime las dos líneas finales, registradas arriba.
