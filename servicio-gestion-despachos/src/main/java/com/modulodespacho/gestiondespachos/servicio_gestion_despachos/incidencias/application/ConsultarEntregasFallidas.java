package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.FiltrosConsultaFallidos.Recepcion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.PaginaEntregasFallidas.Fila;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.PaginaEntregasFallidas.Indicadores;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Lógica de consulta; la entrada HTTP debe autorizar al Gestor antes de invocarla. */
public final class ConsultarEntregasFallidas {

    private static final Comparator<IncidenciaListadoLocal> ORDEN = Comparator
            .comparing(IncidenciaListadoLocal::recibidoEnCentro).reversed()
            .thenComparing(IncidenciaListadoLocal::fechaIncidencia)
            .thenComparing(IncidenciaListadoLocal::idDespacho);
    private final LectorIncidenciasLocales locales;
    private final LectorDatosOperativos operacion;
    private final CatalogoMotivosFallo motivos;
    private final Clock reloj;

    public ConsultarEntregasFallidas(LectorIncidenciasLocales locales, LectorDatosOperativos operacion,
            CatalogoMotivosFallo motivos, Clock reloj) {
        this.locales = locales;
        this.operacion = operacion;
        this.motivos = motivos;
        this.reloj = reloj;
    }

    public PaginaEntregasFallidas consultar(FiltrosConsultaFallidos filtros) {
        if (filtros.motivo() != null && !motivos.existeCodigo(filtros.motivo())) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_FILTROS_INVALIDOS", "El motivo no pertenece al catálogo",
                    List.of(new ErrorDeCampo("motivo", "El código indicado no existe")));
        }
        List<IncidenciaListadoLocal> base = locales.consultarBase(filtros.motivo(), filtros.fechaDesde(), filtros.fechaHasta());
        List<String> idsPendientes = identificadores(base.stream().filter(fila -> !fila.recibidoEnCentro())
                .map(IncidenciaListadoLocal::idJornada).toList());
        Map<String, Jornada> jornadas = new LinkedHashMap<>();
        if (!idsPendientes.isEmpty()) {
            incorporarJornadas(idsPendientes, operacion.consultar(List.of(), idsPendientes), jornadas);
        }

        long pendientes = base.stream().filter(fila -> !fila.recibidoEnCentro()).count();
        long desconocidos = base.stream().filter(fila -> retornoAtrasado(fila, jornadas) == null).count();
        long atrasados = base.stream().filter(fila -> Boolean.TRUE.equals(retornoAtrasado(fila, jornadas))).count();
        Indicadores indicadores = new Indicadores(pendientes, base.size() - pendientes,
                desconocidos == 0 ? Long.valueOf(atrasados) : null, desconocidos);
        if (filtros.soloRetornosAtrasados() && desconocidos > 0) {
            throw ErrorDeNegocio.de(503, "DESP_ERROR_JORNADAS_NO_DISPONIBLES",
                    "No se pudo confirmar el cierre de todas las jornadas pendientes");
        }

        List<IncidenciaListadoLocal> seleccion = base.stream()
                .filter(fila -> filtros.recepcion() == Recepcion.TODOS
                        || fila.recibidoEnCentro() == (filtros.recepcion() == Recepcion.RECIBIDO_EN_CENTRO))
                .filter(fila -> !filtros.soloRetornosAtrasados() || Boolean.TRUE.equals(retornoAtrasado(fila, jornadas)))
                .sorted(ORDEN).toList();
        long inicio = (long) (filtros.pagina() - 1) * filtros.tamanoPagina();
        List<IncidenciaListadoLocal> pagina = seleccion.stream().skip(inicio).limit(filtros.tamanoPagina()).toList();
        List<String> idsRepartidor = identificadores(pagina.stream().map(IncidenciaListadoLocal::idRepartidor).toList());
        List<String> idsAdicionales = identificadores(pagina.stream().map(IncidenciaListadoLocal::idJornada)
                .filter(id -> !jornadas.containsKey(id)).toList());
        DatosOperativos datosPagina = idsRepartidor.isEmpty() && idsAdicionales.isEmpty()
                ? new DatosOperativos(Map.of(), Map.of()) : operacion.consultar(idsRepartidor, idsAdicionales);
        // No se reemplaza la lectura de jornadas pendientes utilizada para los indicadores.
        incorporarJornadas(idsAdicionales, datosPagina, jornadas);
        List<Fila> contenido = pagina.stream().map(fila -> new Fila(
                fila.idDespacho(), fila.idIncidencia(), fila.versionDespacho(), fila.codigoRastreoInterno(),
                fila.fechaIncidencia(), fila.motivo(), fila.numeroIntentos(), fila.maximoIntentos(), fila.consumeIntento(),
                datosPagina.repartidores().getOrDefault(fila.idRepartidor(), new Repartidor(fila.idRepartidor(), null)),
                fila.recibidoEnCentro() ? Recepcion.RECIBIDO_EN_CENTRO : Recepcion.PENDIENTE_RETORNO,
                jornada(fila, jornadas), retornoAtrasado(fila, jornadas), fila.tieneEvidencia(), fila.pedidoAnulado())).toList();
        long total = seleccion.size();
        return new PaginaEntregasFallidas(contenido, filtros.pagina(), filtros.tamanoPagina(), total,
                (total + filtros.tamanoPagina() - 1) / filtros.tamanoPagina(), indicadores, reloj.instant());
    }

    private static void incorporarJornadas(List<String> ids, DatosOperativos datos, Map<String, Jornada> destino) {
        ids.forEach(id -> destino.put(id, datos.jornadas().getOrDefault(id, Jornada.desconocida(id, null))));
    }

    private static List<String> identificadores(Collection<String> ids) {
        return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    private static Jornada jornada(IncidenciaListadoLocal fila, Map<String, Jornada> jornadas) {
        return jornadas.getOrDefault(fila.idJornada(), Jornada.desconocida(fila.idJornada(), null));
    }

    private static Boolean retornoAtrasado(IncidenciaListadoLocal fila, Map<String, Jornada> jornadas) {
        if (fila.recibidoEnCentro()) return false;
        EstadoJornada estado = jornada(fila, jornadas).estado();
        return estado == EstadoJornada.DESCONOCIDA ? null : Boolean.valueOf(estado == EstadoJornada.CERRADA);
    }
}
