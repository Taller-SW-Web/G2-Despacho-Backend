package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.FiltrosPendientes;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public final class ParametrosPendientes {
    private ParametrosPendientes() {}
    public static FiltrosPendientes leer(MultiValueMap<String, String> parametros) {
        Set<String> admitidos = Set.of("pagina", "tamanoPagina", "idZona", "fechaDesde", "fechaHasta", "tipoIntento");
        parametros.forEach((campo, valores) -> {
            if (!admitidos.contains(campo) || valores.size() != 1 || valores.getFirst() == null || valores.getFirst().isBlank()) throw invalido(campo);
        });
        int pagina = entero(parametros.getFirst("pagina"), 1, Integer.MAX_VALUE, "pagina");
        int tamano = entero(parametros.getFirst("tamanoPagina"), 20, 100, "tamanoPagina");
        LocalDate desde = fecha(parametros.getFirst("fechaDesde"), "fechaDesde");
        LocalDate hasta = fecha(parametros.getFirst("fechaHasta"), "fechaHasta");
        if (desde != null && hasta != null && desde.isAfter(hasta)) throw invalido("fechaDesde");
        String intento = parametros.getFirst("tipoIntento");
        if (intento == null) intento = "TODOS";
        if (!Set.of("TODOS", "PRIMER_INTENTO", "REINTENTOS").contains(intento)) throw invalido("tipoIntento");
        return new FiltrosPendientes(pagina, tamano, parametros.getFirst("idZona"), desde, hasta, intento);
    }
    private static int entero(String texto, int defecto, int maximo, String campo) {
        if (texto == null) return defecto;
        try {
            if (!texto.matches("[1-9][0-9]*")) throw invalido(campo);
            int valor = Integer.parseInt(texto);
            if (valor > maximo) throw invalido(campo);
            return valor;
        } catch (NumberFormatException error) { throw invalido(campo); }
    }
    private static LocalDate fecha(String texto, String campo) {
        if (texto == null) return null;
        try {
            if (!texto.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || texto.startsWith("0000")) throw invalido(campo);
            return LocalDate.parse(texto);
        } catch (DateTimeParseException error) { throw invalido(campo); }
    }
    private static ErrorDeNegocio invalido(String campo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_FILTROS_INVALIDOS", "La consulta contiene filtros inválidos",
                List.of(new ErrorDeCampo(campo, "Revisa el valor y el formato del filtro")));
    }
}
