package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.FiltrosConsultaFallidos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.FiltrosConsultaFallidos.Recepcion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

final class ParametrosConsultaFallidos {

    private static final Set<String> PERMITIDOS = Set.of("pagina", "tamanoPagina", "motivo", "fechaDesde",
            "fechaHasta", "recepcion", "soloRetornosAtrasados");

    private ParametrosConsultaFallidos() {}

    static FiltrosConsultaFallidos convertir(MultiValueMap<String, String> parametros) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        parametros.forEach((campo, valores) -> {
            if (!PERMITIDOS.contains(campo)) errores.add(new ErrorDeCampo(campo, "Parámetro no admitido"));
            if (valores == null || valores.size() != 1) {
                errores.add(new ErrorDeCampo(campo, "Debe aparecer una sola vez"));
            } else if (valores.getFirst() == null || valores.getFirst().isBlank()) {
                errores.add(new ErrorDeCampo(campo, "No puede estar vacío"));
            }
        });
        comprobar(errores);
        int pagina = entero(parametros, "pagina", 1, errores);
        int tamano = entero(parametros, "tamanoPagina", 20, errores);
        LocalDate desde = fecha(parametros, "fechaDesde", errores);
        LocalDate hasta = fecha(parametros, "fechaHasta", errores);
        Recepcion recepcion = Recepcion.TODOS;
        String valorRecepcion = parametros.getFirst("recepcion");
        if (valorRecepcion != null) {
            try {
                recepcion = Recepcion.valueOf(valorRecepcion);
            } catch (IllegalArgumentException error) {
                errores.add(new ErrorDeCampo("recepcion", "Valor de recepción no admitido"));
            }
        }
        String soloAtrasados = parametros.getFirst("soloRetornosAtrasados");
        if (soloAtrasados != null && !soloAtrasados.equals("true") && !soloAtrasados.equals("false")) {
            errores.add(new ErrorDeCampo("soloRetornosAtrasados", "Debe ser true o false"));
        }
        comprobar(errores);
        return new FiltrosConsultaFallidos(pagina, tamano, parametros.getFirst("motivo"), desde, hasta,
                recepcion, "true".equals(soloAtrasados));
    }

    private static int entero(MultiValueMap<String, String> parametros, String campo, int porDefecto,
            List<ErrorDeCampo> errores) {
        String valor = parametros.getFirst(campo);
        if (valor == null) return porDefecto;
        try {
            if (valor.matches("[0-9]+")) return Integer.parseInt(valor);
        } catch (NumberFormatException error) {
            // El formato decimal también debe caber en un entero de Java.
        }
        errores.add(new ErrorDeCampo(campo, "Debe ser un entero válido"));
        return porDefecto;
    }

    private static LocalDate fecha(MultiValueMap<String, String> parametros, String campo, List<ErrorDeCampo> errores) {
        String valor = parametros.getFirst(campo);
        if (valor == null) return null;
        try {
            if (valor.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(valor);
        } catch (DateTimeParseException error) {
            // Una fecha con formato correcto también debe existir en el calendario.
        }
        errores.add(new ErrorDeCampo(campo, "Debe ser una fecha válida con formato YYYY-MM-DD"));
        return null;
    }

    private static void comprobar(List<ErrorDeCampo> errores) {
        if (!errores.isEmpty()) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_FILTROS_INVALIDOS", "Los filtros no son válidos", List.copyOf(errores));
        }
    }
}
