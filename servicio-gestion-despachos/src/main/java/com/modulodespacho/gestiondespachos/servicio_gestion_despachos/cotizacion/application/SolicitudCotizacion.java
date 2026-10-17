package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import java.util.List;

public record SolicitudCotizacion(Destino destino, List<Linea> lineas) {
    public record Destino(String direccion, String distrito, String codigoPostal) {}
    public record Linea(String sku, Integer cantidad) {}
}
