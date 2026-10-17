package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;

public final class ConsultarDetalleIncidencia {
    private final LectorDetalleIncidencia locales;
    private final LectorDatosOperativos operacion;
    private final Clock reloj;
    private final ExecutorService consultas;

    public ConsultarDetalleIncidencia(LectorDetalleIncidencia locales, LectorDatosOperativos operacion, Clock reloj, ExecutorService consultas) {
        this.locales = locales; this.operacion = operacion; this.reloj = reloj; this.consultas = consultas;
    }

    public DetalleIncidencia consultar(String idDespacho) {
        var local = locales.consultar(idDespacho);
        var incidencia = local.incidencia();
        String idRepartidor = incidencia.repartidor().idRepartidor();
        String idJornada = incidencia.jornada().idJornada();
        // La transacción de locales ya terminó. Un solo lote reutiliza la degradación de ES-F04-01.
        var pendiente = consultas.submit(() -> operacion.consultar(List.of(idRepartidor), List.of(idJornada)));
        DatosOperativos datos;
        try { datos = pendiente.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); pendiente.cancel(true); datos = new DatosOperativos(Map.of(), Map.of());
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            pendiente.cancel(true); datos = new DatosOperativos(Map.of(), Map.of());
        }
        var jornada = datos.jornadas().getOrDefault(idJornada, Jornada.desconocida(idJornada, null));
        var repartidor = datos.repartidores().getOrDefault(idRepartidor, new Repartidor(idRepartidor, null));
        Boolean atrasado = "RECIBIDO_EN_CENTRO".equals(incidencia.recepcion().estado()) ? Boolean.FALSE
                : jornada.estado() == EstadoJornada.DESCONOCIDA ? null : Boolean.valueOf(jornada.estado() == EstadoJornada.CERRADA);
        var enriquecida = new DetalleIncidencia.Incidencia(incidencia.idIncidencia(), incidencia.fechaIncidencia(),
                incidencia.motivo(), incidencia.comentario(), incidencia.consumeIntento(), repartidor, jornada,
                atrasado, incidencia.recepcion(), incidencia.evidencia());
        return new DetalleIncidencia(local.idDespacho(), local.codigoRastreoInterno(), local.estado(), local.versionDespacho(),
                local.actualizadoEn(), local.ultimoCambioEstadoEn(), reloj.instant(), local.numeroIntentos(),
                local.maximoIntentos(), local.pedidoAnulado(), enriquecida, local.historial(), local.comunicaciones(), local.acciones());
    }
}
