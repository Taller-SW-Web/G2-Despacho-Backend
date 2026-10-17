package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure;

import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

/** Consulta previa con la identidad administrativa; nunca comparte tablas de Gestión. */
public class ConsultaZonaHttp {
    private final RestClient cliente;
    public ConsultaZonaHttp(RestClient cliente) { this.cliente = cliente; }
    public void verificar(UUID zona,String token) {
        try {
            JsonNode respuesta = cliente.get().uri("/api/v1/zonas/{id}",zona).headers(c -> c.setBearerAuth(token)).retrieve().body(JsonNode.class);
            if (respuesta == null || !zona.toString().equals(respuesta.path("id").asText()) || !respuesta.path("estado").isTextual()) throw ErrorJornada.noDisponible();
            if ("INACTIVO".equals(respuesta.path("estado").asText())) throw ErrorJornada.noApto("La zona está inactiva.");
            if (!"ACTIVO".equals(respuesta.path("estado").asText())) throw ErrorJornada.noDisponible();
        } catch (ErrorJornada error) { throw error; }
        catch (HttpClientErrorException.NotFound error) { throw ErrorJornada.noApto("La zona no existe."); }
        catch (RuntimeException error) { throw ErrorJornada.noDisponible(); }
    }
}
