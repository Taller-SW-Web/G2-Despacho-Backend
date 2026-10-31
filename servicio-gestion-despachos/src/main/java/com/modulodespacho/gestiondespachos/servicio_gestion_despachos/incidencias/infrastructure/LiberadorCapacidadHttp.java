package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberadorCapacidad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberacionesPendientes;

public final class LiberadorCapacidadHttp implements LiberadorCapacidad {
    private final RestClient cliente;
    private final Supplier<String> token;
    // Únicamente el perfil demo local omite credencial, conforme al resto de adaptadores.
    private final boolean sinAutenticacion;
    public LiberadorCapacidadHttp(RestClient cliente, Supplier<String> token, boolean sinAutenticacion) {
        this.cliente = cliente; this.token = token; this.sinAutenticacion = sinAutenticacion;
    }
    @Override public Resultado liberar(LiberacionesPendientes.Envio envio) {
        String credencial = token.get();
        if (!sinAutenticacion && (credencial == null || credencial.isBlank())) return Resultado.fallo(true, "CREDENCIAL_NO_DISPONIBLE");
        try {
            return cliente.post().uri("/internal/v1/capacidad/reservas/{id}/liberacion", envio.reserva())
                    .headers(h -> { if (!sinAutenticacion) h.setBearerAuth(credencial); })
                    .body(Map.of("motivo", "RECEPCION_EN_CENTRO", "idDespacho", envio.despacho()))
                    .exchange((solicitud, respuesta) -> {
                        int codigo = respuesta.getStatusCode().value();
                        if (codigo != 200) return Resultado.fallo(codigo < 500 && codigo != 429, "HTTP_" + codigo);
                        try {
                            var dato = respuesta.bodyTo(tools.jackson.databind.JsonNode.class);
                            if (dato == null || !dato.path("idReserva").isString() || !dato.path("idDespacho").isString()
                                    || !dato.path("estado").isString() || !dato.path("motivoLiberacion").isString()
                                    || !dato.path("liberadaEn").isString()
                                    || !envio.reserva().equals(dato.path("idReserva").asString())
                                    || !envio.despacho().equals(dato.path("idDespacho").asString())
                                    || !"LIBERADA".equals(dato.path("estado").asString())
                                    || !Set.of("ENTREGA", "CANCELACION", "RECEPCION_EN_CENTRO", "REASIGNACION", "COMPENSACION")
                                        .contains(dato.path("motivoLiberacion").asString()))
                                return Resultado.fallo(true, "RESPUESTA_INCOHERENTE");
                            return new Resultado(true, false, null, Instant.parse(dato.path("liberadaEn").asString()));
                        } catch (RuntimeException error) { return Resultado.fallo(true, "RESPUESTA_INCOHERENTE"); }
                    });
        } catch (RestClientResponseException error) {
            int codigo = error.getStatusCode().value();
            return Resultado.fallo(codigo >= 400 && codigo < 500 && codigo != 429, "HTTP_" + codigo);
        } catch (RuntimeException error) { return Resultado.fallo(false, "OPERACION_NO_DISPONIBLE"); }
    }
}
