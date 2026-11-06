package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.UUID;

/** Captura interna de identidad y condiciones; independiente de futuras sustituciones. */
public record TarifaAplicada(UUID id, UUID idZona, TarifaVista condiciones) {}
