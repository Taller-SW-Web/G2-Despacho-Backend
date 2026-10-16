package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Instant;
import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.FiltrosConsultaFallidos.Recepcion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal.Motivo;

public record PaginaEntregasFallidas(List<Fila> contenido, int pagina, int tamanoPagina,
        long totalElementos, long totalPaginas, Indicadores indicadores, Instant consultadoEn) {

    public PaginaEntregasFallidas {
        contenido = List.copyOf(contenido);
    }

    public record Indicadores(long pendientesRetorno, long recibidosCentro, Long retornosAtrasados,
            long retornosSinJornadaConfirmada) {}

    public record Fila(String idDespacho, String idIncidencia, int versionDespacho,
            String codigoRastreoInterno, Instant fechaIncidencia, Motivo motivo,
            int numeroIntentos, int maximoIntentos, boolean consumeIntento, Repartidor repartidor,
            Recepcion recepcion, Jornada jornada, Boolean retornoAtrasado, boolean tieneEvidencia,
            boolean pedidoAnulado) {}
}
