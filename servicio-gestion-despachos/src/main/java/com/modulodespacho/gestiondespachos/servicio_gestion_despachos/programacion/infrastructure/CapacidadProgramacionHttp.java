package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.PuertoCapacidad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

public final class CapacidadProgramacionHttp implements PuertoCapacidad {
    private final RestClient cliente;
    private final Supplier<String> token;
    private final boolean sinAutenticacion;
    public CapacidadProgramacionHttp(RestClient cliente, Supplier<String> token) { this(cliente, token, false); }
    public CapacidadProgramacionHttp(RestClient cliente, Supplier<String> token, boolean sinAutenticacion) {
        this.cliente = cliente; this.token = token; this.sinAutenticacion = sinAutenticacion;
    }
    @Override public ReservaConfirmada reservar(IntencionAsignacion intencion) {
        try {
            var respuestaJson = cliente.post().uri("/internal/v1/capacidad/reservas")
                    .headers(cabeceras -> { autorizar(cabeceras); cabeceras.set("Idempotency-Key", intencion.id().toString()); })
                    .body(Map.of("idDespacho", intencion.idDespacho().toString(), "idRepartidor", intencion.idRepartidor(),
                            "idJornada", intencion.idJornada(), "requerido", intencion.carga()))
                    .retrieve().onStatus(estado -> estado.value() != 200, (solicitud, resultado) -> {
                        int estado = resultado.getStatusCode().value();
                        if (estado == 422) {
                            var cuerpo = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resultado.getBody());
                            var errores = new java.util.ArrayList<com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo>();
                            if (cuerpo != null) for (var dato : cuerpo.path("errores")) {
                                String campo = dato.path("campo").asText();
                                if (java.util.Set.of("pesoKg", "volumenM3", "paquetes").contains(campo)) errores.add(
                                        new com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo(campo, "Supera la capacidad remanente"));
                            }
                            String variables = errores.stream().map(error -> switch (error.campo()) {
                                case "pesoKg" -> "peso"; case "volumenM3" -> "volumen"; default -> "paquetes";
                            }).distinct().collect(java.util.stream.Collectors.joining(", "));
                            throw ErrorDeNegocio.de(422, "DESP_ERROR_CAPACIDAD_EXCEDIDA", "Capacidad de carga del repartidor excedida"
                                    + (variables.isEmpty() ? "" : ": " + variables), errores);
                        }
                        if (estado == 409) {
                            var cuerpo = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resultado.getBody());
                            // Solo el código acordado determina reserva previa; no se propaga texto remoto.
                            boolean anterior = cuerpo != null && "DESP_ERROR_RESERVA_ANTERIOR_ACTIVA".equals(cuerpo.path("code").asText());
                            throw ErrorDeNegocio.de(409, anterior ? "DESP_ERROR_RESERVA_ANTERIOR_ACTIVA" : "DESP_ERROR_REPARTIDOR_NO_HABILITADO",
                                    anterior ? "La reserva anterior aún está activa" : "El repartidor no está habilitado para la asignación");
                        }
                        throw noDisponible();
                    }).body(tools.jackson.databind.JsonNode.class);
            if (respuestaJson == null || !respuestaJson.path("idReserva").isTextual() || respuestaJson.path("idReserva").asText().isBlank()
                    || !"CONFIRMADA".equals(respuestaJson.path("estado").asText())) throw noDisponible();
            var remanente = respuestaJson.path("capacidadRemanente");
            if (!remanente.path("pesoKg").isNumber() || !remanente.path("volumenM3").isNumber()
                    || !remanente.path("paquetes").isIntegralNumber() || !remanente.path("paquetes").canConvertToInt()
                    || remanente.path("pesoKg").decimalValue().signum() < 0 || remanente.path("volumenM3").decimalValue().signum() < 0
                    || remanente.path("paquetes").intValue() < 0) throw noDisponible();
            return new ReservaConfirmada(respuestaJson.path("idReserva").asText(), "CONFIRMADA", new Carga(
                    remanente.path("pesoKg").decimalValue(), remanente.path("volumenM3").decimalValue(), remanente.path("paquetes").intValue()));
        } catch (ErrorDeNegocio error) { throw error; }
        catch (RuntimeException error) { throw noDisponible(); }
    }
    @Override public void compensar(String reserva, String despacho) {
        try {
            Liberacion respuesta = cliente.post().uri("/internal/v1/capacidad/reservas/{id}/liberacion", reserva)
                    .headers(this::autorizar)
                    .body(Map.of("motivo", "COMPENSACION", "idDespacho", despacho)).retrieve()
                    .onStatus(estado -> estado.value() != 200, (solicitud, resultado) -> { throw noDisponible(); }).body(Liberacion.class);
            if (respuesta == null || !reserva.equals(respuesta.idReserva()) || !despacho.equals(respuesta.idDespacho())
                    || !"LIBERADA".equals(respuesta.estado()) || respuesta.liberadaEn() == null
                    || respuesta.motivoLiberacion() == null || respuesta.motivoLiberacion().isBlank()) throw noDisponible();
            java.time.Instant.parse(respuesta.liberadaEn());
        } catch (RuntimeException error) { throw noDisponible(); }
    }
    private void autorizar(org.springframework.http.HttpHeaders cabeceras) {
        if (!sinAutenticacion) cabeceras.setBearerAuth(credencial());
    }
    private String credencial() { String valor = token.get(); if (valor == null || valor.isBlank()) throw noDisponible(); return valor; }
    private static ErrorDeNegocio noDisponible() { return ErrorDeNegocio.de(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo confirmar la operación de capacidad"); }
    public record Liberacion(String idReserva, String idDespacho, String estado, String motivoLiberacion, String liberadaEn) {}
}
