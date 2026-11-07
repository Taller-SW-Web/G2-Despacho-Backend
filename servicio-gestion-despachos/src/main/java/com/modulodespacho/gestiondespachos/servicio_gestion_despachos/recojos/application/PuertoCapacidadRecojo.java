package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Operación §5.1: la clave vuelve idempotente la reserva, también durante la recuperación. */
public interface PuertoCapacidadRecojo {
    /** Devuelve el identificador de la reserva confirmada. */
    String reservarRecojo(UUID clave, UUID idRecojo, UUID idDespacho, String idRepartidor, String idJornada,
            BigDecimal pesoKg, BigDecimal volumenM3, int paquetes);
    void compensarRecojo(String idReserva, String idDespacho);
}
