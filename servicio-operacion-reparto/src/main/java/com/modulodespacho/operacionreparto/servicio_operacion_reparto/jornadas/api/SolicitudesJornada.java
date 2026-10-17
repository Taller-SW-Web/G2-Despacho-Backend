package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada;

public final class SolicitudesJornada {
    private SolicitudesJornada() {}
    public record Abrir(@NotNull UUID idRepartidor,@NotNull UUID idFurgoneta,@NotNull UUID idZona,@NotNull LocalDate fecha) {
        public DatosJornada.Apertura datos() { return new DatosJornada.Apertura(idRepartidor,idFurgoneta,idZona,fecha); }
    }
    public record Carga(@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=7,fraction=3) BigDecimal pesoKg,
            @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=6,fraction=4) BigDecimal volumenM3,
            @NotNull @Min(1) @Max(32767) Integer paquetes) {
        public DatosJornada.Capacidad datos() { return new DatosJornada.Capacidad(pesoKg,volumenM3,paquetes); }
    }
    public record Reservar(@NotNull UUID idDespacho,@NotNull UUID idRepartidor,@NotNull UUID idJornada,@NotNull @Valid Carga requerido) {
        public DatosJornada.SolicitudReserva datos() { return new DatosJornada.SolicitudReserva(idDespacho,idRepartidor,idJornada,requerido.datos()); }
    }
    public record Liberar(@NotNull UUID idDespacho,@NotNull @Pattern(regexp="ENTREGA|CANCELACION|RECEPCION_EN_CENTRO|REASIGNACION|COMPENSACION") String motivo) {}
    public record CierrePropio(@NotNull UUID idJornada) {}
    public record Destinatario(@NotBlank @Size(max=160) String nombre,@NotBlank @Size(max=30) String telefono) {
        DatosJornada.Destinatario datos() { return new DatosJornada.Destinatario(nombre,telefono); }
    }
    public record Destino(@NotBlank @Size(max=300) String direccion,@Size(max=300) String referencia,
            @Size(max=100) String departamento,@Size(max=100) String provincia,@NotBlank @Size(max=100) String distrito,
            @Size(max=20) String codigoPostal,Object coordenadas) {
        DatosJornada.Destino datos() { return new DatosJornada.Destino(direccion,referencia,departamento,provincia,distrito,codigoPostal,coordenadas); }
    }
    public record Proyectar(@NotNull UUID idDespacho,@NotBlank @Size(max=50) String idPedido,@NotBlank @Size(max=20) String codigoRastreoInterno,
            @NotNull @Pattern(regexp="PENDIENTE_ASIGNACION|ASIGNADO|EN_CAMINO|ENTREGADO|FALLIDO|CANCELADO|DEVUELTO_A_ORIGEN") String estado,
            @NotNull UUID idJornada,@NotNull UUID idReserva,@Min(1) @Max(32767) Integer secuenciaRuta,
            @NotNull @Valid Destinatario destinatario,@NotNull @Valid Destino destino,@NotNull LocalDate fechaProgramada,
            @Min(0) int numeroIntentos,@NotNull @Min(0) Long version,@NotNull Instant actualizadoEn) {
        public DatosJornada.Proyeccion datos() { return new DatosJornada.Proyeccion(idDespacho,idPedido,codigoRastreoInterno,estado,
                idJornada,idReserva,secuenciaRuta,destinatario.datos(),destino.datos(),fechaProgramada,numeroIntentos,version,actualizadoEn); }
    }
}
