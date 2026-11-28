# INT-08 · Un repartidor no ve el detalle de un despacho ajeno

| Campo | Valor |
|---|---|
| **Caso / responsable** | INT-08 · Persona A |
| **Fecha y commit** | 2026-11-26 03:10 (America/Lima) · `9372386`, igual que INT-02 y en la misma corrida. |
| **Comando y entorno** | Igual que [INT-02](INT-02.md). |
| **Datos sintéticos** | Los de INT-02. B (perfil `sin-rol`, otra identidad, vinculado y con su propia jornada) pide el despacho `3468b58f-…` de A. Los datos personales esperados se leen del detalle que ve A: destinatario, teléfono, dirección y código de rastreo de la simulación. |
| **Entrada** | `GET /api/v1/repartidor/despachos/{idDespacho}` por Gateway. |
| **Aserciones (9)** | Control: A ve su detalle con destinatario, teléfono y dirección. Control: B ve su propio despacho (`200`), así que su sesión es válida. B pide el de A → **`404`**. El cuerpo **no contiene** el destinatario, el teléfono, la dirección ni el código de rastreo de A, ni los campos `destinatario`, `destino`, `secuenciaRuta`, `idJornada` o `hitos`. Un ID inexistente responde igual que uno ajeno, así que no se revela que el despacho existe. |
| **Resultado observado** | Las 9 aserciones correctas. Cuerpo del `404` con el formato de error del contrato: `code` = `DESP_ERROR_DESPACHO_NO_ENCONTRADO`, sin datos del despacho. Surefire: 3 tests de `Int08`, 0 fallos. El `404` coincide con el paso `despacho_ajeno` del verificador Python sobre datos recreados: ver [COMPARACION-PYTHON](COMPARACION-PYTHON.md). |
| **Resultado** | **Aprobado** |
| **Evidencia** | [`RutasRepartidorF02Test.java`](../integracion-java/src/test/java/com/modulodespacho/pruebas/integracion/f02/RutasRepartidorF02Test.java) · [informe JSON](evidencia/int-02-int-08.json) · [Surefire](evidencia/TEST-com.modulodespacho.pruebas.integracion.f02.RutasRepartidorF02Test$Int08.xml) |
