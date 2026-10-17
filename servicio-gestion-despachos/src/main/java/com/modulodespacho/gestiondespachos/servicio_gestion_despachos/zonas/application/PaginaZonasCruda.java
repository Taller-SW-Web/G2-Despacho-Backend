package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.List;

public record PaginaZonasCruda(
        List<com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona> contenido,
        int pagina,
        int tamanoPagina,
        long totalElementos,
        int totalPaginas) {
}
