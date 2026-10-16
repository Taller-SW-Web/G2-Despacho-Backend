package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public record FiltrosConsultaFallidos(int pagina, int tamanoPagina, String motivo,
        LocalDate fechaDesde, LocalDate fechaHasta, Recepcion recepcion, boolean soloRetornosAtrasados) {

    public enum Recepcion { TODOS, PENDIENTE_RETORNO, RECIBIDO_EN_CENTRO }

    public FiltrosConsultaFallidos {
        recepcion = recepcion == null ? Recepcion.TODOS : recepcion;
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (pagina < 1) errores.add(new ErrorDeCampo("pagina", "Debe ser mayor o igual a 1"));
        if (tamanoPagina < 1 || tamanoPagina > 100) {
            errores.add(new ErrorDeCampo("tamanoPagina", "Debe estar entre 1 y 100"));
        }
        if (motivo != null && motivo.isBlank()) errores.add(new ErrorDeCampo("motivo", "No puede estar vacío"));
        if (fechaDesde != null && fechaHasta != null && fechaDesde.isAfter(fechaHasta)) {
            errores.add(new ErrorDeCampo("fechaHasta", "No puede ser anterior a fechaDesde"));
        }
        if (soloRetornosAtrasados && recepcion == Recepcion.RECIBIDO_EN_CENTRO) {
            errores.add(new ErrorDeCampo("soloRetornosAtrasados", "Es incompatible con recibido en centro"));
        }
        if (!errores.isEmpty()) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_FILTROS_INVALIDOS", "Los filtros no son válidos", List.copyOf(errores));
        }
    }

    public static FiltrosConsultaFallidos iniciales() {
        return new FiltrosConsultaFallidos(1, 20, null, null, null, Recepcion.TODOS, false);
    }
}
