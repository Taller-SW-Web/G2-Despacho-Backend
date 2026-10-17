package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.time.Instant;
import java.util.UUID;

public interface AutorizadorCargaStorage {
    AutorizacionStorage autorizar(UUID idDespacho, UUID idEvidencia);

    record AutorizacionStorage(String urlCarga, Instant venceEn) {}
}
