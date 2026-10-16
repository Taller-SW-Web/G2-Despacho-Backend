package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.List;

public record PaginaZonas(
        List<ZonaVista> contenido,
        int pagina,
        int tamanoPagina,
        long totalElementos,
        int totalPaginas) {
}
