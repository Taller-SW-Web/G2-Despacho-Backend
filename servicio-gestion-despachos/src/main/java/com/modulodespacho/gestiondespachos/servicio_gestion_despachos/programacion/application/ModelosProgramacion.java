package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Modelos de entrada/salida de F-02; nunca se serializan las entidades gestionadas. */
public final class ModelosProgramacion {
    private ModelosProgramacion() {}

    public record Destinatario(@NotBlank String nombre, @NotBlank String telefono) {}
    public record Coordenadas(@NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitud,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitud) {}
    public record Destino(@NotBlank String direccion, String referencia, String departamento, String provincia,
            @NotBlank String distrito, String codigoPostal, @Valid Coordenadas coordenadas) {}
    public record Linea(@NotBlank String sku, @NotNull @Positive Integer cantidad) {}
    public record SolicitudRecepcion(@NotBlank String idPedido, @NotNull @Valid Destinatario destinatario,
            @NotNull @Valid Destino destino, @NotEmpty List<@NotNull @Valid Linea> lineas) {}
    public record SolicitudSimulacion(String idZona, @Positive BigDecimal pesoKg,
            @Positive BigDecimal volumenM3, String observaciones) {}
    public record SolicitudAsignacion(@NotBlank String idRepartidor, @NotBlank String idJornada, String observacion) {}
    public record RespuestaCreacion(String idDespacho, String idPedido, String codigoRastreoInterno,
            String estado, String idZona, LocalDate fechaProgramada, Instant creadoEn, String codigoOperativo,
            boolean esSimulacion, BigDecimal pesoTotalKg, BigDecimal volumenTotalM3, int cantidadPaquetes) {}
    public record ResultadoCreacion(int codigoHttp, RespuestaCreacion respuesta) {}
    public record Carga(BigDecimal pesoKg, BigDecimal volumenM3, Integer paquetes) {}
    public record ZonaDisponible(UUID id, String nombre, String distrito, String codigoPostal, int plazoDiasHabiles) {}
    public record ReservaConfirmada(String idReserva, String estado, Carga capacidadRemanente) {}
    public record RespuestaAsignacion(String idDespacho, String estado, int versionDespacho, String idRepartidor,
            String idJornada, String idReserva, int posicionSecuencia, String observacion, Instant asignadoEn) {}
    public record IntencionAsignacion(UUID id, UUID idDespacho, int versionOrigen, String ejecutor,
            String idRepartidor, String idJornada, String observacion, String estado, String idReserva,
            RespuestaAsignacion respuesta, Carga carga) {}
    public record FiltrosPendientes(int pagina, int tamanoPagina, String idZona, LocalDate fechaDesde,
            LocalDate fechaHasta, String tipoIntento) {}
    public record Pendiente(String idDespacho, String codigoOperativo, String codigoRastreoInterno,
            String idPedido, String idZona, String nombreZona, String direccion, BigDecimal pesoTotalKg,
            BigDecimal volumenTotalM3, int cantidadPaquetes, LocalDate fechaProgramada, int numeroIntentos,
            long tiempoEsperaSegundos, boolean esSimulacion, boolean reintento, boolean asignable,
            LocalDate disponibleDesde, int versionDespacho) {}
    public record Indicadores(long pendientesHoy, long programadosFuturo, long reintentos) {}
    public record PaginaPendientes(List<Pendiente> contenido, int paginaActual, int tamanoPagina,
            long totalElementos, long totalPaginas, Indicadores indicadores, LocalDate fechaActualServidor, Instant consultadoEn) {}
}
