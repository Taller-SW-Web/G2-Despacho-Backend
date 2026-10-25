package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.infrastructure;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.PuertoGestion;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

/** Cliente de Gestión §6.1 y §4; solo los códigos acordados se propagan, nunca el texto remoto. */
public class GestionHttp implements PuertoGestion {
    private static final Set<String> CONFLICTOS = Set.of("DESP_ERROR_ESTADO_INCOMPATIBLE", "DESP_ERROR_DESPACHO_REASIGNADO");
    private final RestClient cliente;
    private final Supplier<String> token;
    private final boolean sinAutenticacion;
    private final JsonMapper json;
    public GestionHttp(RestClient cliente, Supplier<String> token, boolean sinAutenticacion, JsonMapper json) {
        this.cliente = cliente; this.token = token; this.sinAutenticacion = sinAutenticacion; this.json = json;
    }

    @Override
    public RespuestaTransicion transicionar(UUID idDespacho, UUID clave, SolicitudTransicion solicitud) {
        try {
            RespuestaTransicion respuesta = cliente.post().uri("/internal/v1/despachos/{id}/transiciones", idDespacho)
                    .headers(cabeceras -> { autorizar(cabeceras); cabeceras.set("Idempotency-Key", clave.toString()); })
                    .contentType(MediaType.APPLICATION_JSON).body(solicitud).retrieve().body(RespuestaTransicion.class);
            if (respuesta == null || !idDespacho.toString().equals(respuesta.idDespacho())
                    || !solicitud.estadoDestino().equals(respuesta.estado()) || respuesta.registradoEn() == null) throw noDisponible();
            return respuesta;
        } catch (RestClientResponseException error) {
            int estado = error.getStatusCode().value();
            String codigo = codigo(error);
            if (estado == 409 && CONFLICTOS.contains(codigo)) throw new ErrorJornada(409, codigo,
                    "El despacho fue actualizado y ya no admite esta acción. Actualiza tu ruta.");
            if (estado == 404) throw new ErrorJornada(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe.");
            if (estado == 400 && "DESP_ERROR_MOTIVO_INVALIDO".equals(codigo))
                throw new ErrorJornada(400, codigo, "El motivo no se puede registrar en campo.");
            if (estado == 400) throw new ErrorJornada(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Revisa los datos de la operación.");
            throw noDisponible();
        } catch (ErrorJornada error) { throw error; }
        catch (RuntimeException error) { throw noDisponible(); }
    }

    @Override
    public List<Motivo> motivos() {
        try {
            Catalogo catalogo = cliente.get().uri("/internal/v1/catalogos/motivos-fallo").headers(this::autorizar)
                    .retrieve().body(Catalogo.class);
            if (catalogo == null || catalogo.motivos() == null) throw noDisponible();
            return catalogo.motivos();
        } catch (ErrorJornada error) { throw error; }
        catch (RuntimeException error) { throw noDisponible(); }
    }

    private void autorizar(org.springframework.http.HttpHeaders cabeceras) {
        if (sinAutenticacion) return;
        String valor = token.get();
        if (valor == null || valor.isBlank()) throw noDisponible();
        cabeceras.setBearerAuth(valor);
    }
    private String codigo(RestClientResponseException error) {
        try { return json.readTree(error.getResponseBodyAsString()).path("code").asString(); }
        catch (RuntimeException ignorado) { return ""; }
    }
    private static ErrorJornada noDisponible() {
        return new ErrorJornada(503, "DESP_ERROR_GESTION_NO_DISPONIBLE", "No se pudo registrar el cambio. Reintenta; no se duplicará.");
    }
    public record Catalogo(List<Motivo> motivos) {}
}
