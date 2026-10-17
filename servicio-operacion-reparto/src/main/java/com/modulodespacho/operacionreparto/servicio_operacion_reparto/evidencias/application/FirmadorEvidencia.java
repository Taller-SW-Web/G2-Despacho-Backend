package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.time.Instant;

public interface FirmadorEvidencia {
    AccesoObjeto firmar(String rutaObjeto, int vigenciaSegundos);
    record AccesoObjeto(String urlLectura, Instant venceEn) {}
}
