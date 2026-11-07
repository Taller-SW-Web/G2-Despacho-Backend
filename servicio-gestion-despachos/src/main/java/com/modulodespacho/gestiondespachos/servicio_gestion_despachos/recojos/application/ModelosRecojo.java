package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Modelos de los recojos de devoluciones (Gestión §3.3 y §6.3); el despacho original nunca cambia. */
public final class ModelosRecojo {
    private ModelosRecojo() {}

    public record Linea(@NotBlank @Size(max = 100) String sku, @NotNull @Positive Integer cantidad) {}
    public record SolicitudSimulacion(List<@NotNull @Valid Linea> lineas) {}
    public record SolicitudAsignacion(@NotBlank String idRepartidor, @NotBlank String idJornada) {}
    public record SolicitudRecepcion(@Size(max = 500) String observacion) {}
    public record SolicitudReprogramacion(@NotNull LocalDate fechaProgramada, @Size(max = 500) String observacion) {}
    public record SolicitudCierre(@NotBlank @Size(max = 500) String motivoCierre) {}

    public record SolicitudTransicion(String estadoDestino, String idRepartidor, String idJornada, String idEvidencia,
            String motivo, String comentario, String ejecutor, String origen) {}
    public record Hitos(Instant asignadoEn, Instant enCaminoEn, Instant recogidoEn, Instant fallidoEn) {}
    public record RespuestaTransicion(String idRecojo, String estadoAnterior, String estado, int version,
            int numeroIntentos, String motivoFallo, Hitos hitos, Instant registradoEn) {}

    public record Recojo(String idRecojo, String idDespacho, String codigoRastreoInterno, String estado, boolean esSimulado,
            String destinatario, String telefono, String direccion, String referencia, String distrito, String idZona,
            List<Linea> lineas, BigDecimal pesoKg, BigDecimal volumenM3, int cantidadPaquetes, LocalDate fechaProgramada,
            String idRepartidor, String idJornada, Integer secuenciaRuta, int numeroIntentos, String motivoFallo, String etiquetaMotivo,
            String idEvidencia, String motivoCierre, Instant recibidoEnCentroEn, int version, Instant actualizadoEn) {}
    public record Pagina(List<Recojo> contenido, int pagina, int tamanoPagina, long totalElementos, long totalPaginas) {}
    public record DespachoElegible(String idDespacho, String codigoRastreoInterno, String destinatario, String direccion, String distrito) {}
    public record CambioHistorial(String estadoAnterior, String estadoNuevo, String ejecutor, String motivo, String observaciones, Instant registradoEn) {}
    public record DetalleRecojo(Recojo recojo, List<CambioHistorial> historial) {}

    public record Intencion(UUID id, UUID idRecojo, UUID idDespacho, UUID clave, int versionOrigen, String ejecutor,
            String idRepartidor, String idJornada, String estado, String idReserva, Recojo respuesta,
            BigDecimal pesoKg, BigDecimal volumenM3, int paquetes) {}
}
