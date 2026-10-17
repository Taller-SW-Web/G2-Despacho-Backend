package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;

public final class SolicitudesFlota {
    private SolicitudesFlota() {}
    public record NuevoRepartidor(@NotBlank @Size(max = 80) String nombres,
            @NotBlank @Size(max = 80) String apellidos, @NotNull @Pattern(regexp = "[0-9]{8}") String dni,
            @NotBlank @Size(max = 20) @Pattern(regexp = "^\\+?(?=(?:\\D*\\d){6})[\\d ()-]{6,20}$") String telefono,
            @NotBlank @Email @Size(max = 120) String correo,
            @NotBlank @Size(max = 20) String numeroBrevete, @NotNull Turno turnoHabitual) {
        public AltaRepartidor datos() {
            return new AltaRepartidor(nombres, apellidos, dni, new Contacto(telefono, correo, numeroBrevete, turnoHabitual));
        }
    }
    public record EditarRepartidor(@NotBlank @Size(max = 20) @Pattern(regexp = "^\\+?(?=(?:\\D*\\d){6})[\\d ()-]{6,20}$") String telefono,
            @NotBlank @Email @Size(max = 120) String correo,
            @NotBlank @Size(max = 20) String numeroBrevete, @NotNull Turno turnoHabitual) {
        public Contacto datos() { return new Contacto(telefono, correo, numeroBrevete, turnoHabitual); }
    }
    public record Baja(@NotNull @Pattern(regexp = "INACTIVO") String estado) {}
    public record Vincular(@NotNull UUID usuarioId) {}
    public record NuevaFurgoneta(@NotBlank @Size(max = 10) String placa, @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 3) BigDecimal capacidadPesoKg,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 4) BigDecimal capacidadVolumenM3,
            @NotNull @Min(1) @Max(32767) Integer maximoPaquetes) {
        public Capacidad datos() { return new Capacidad(capacidadPesoKg, capacidadVolumenM3, maximoPaquetes); }
    }
    public record EditarFurgoneta(@NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 3) BigDecimal capacidadPesoKg,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 4) BigDecimal capacidadVolumenM3,
            @NotNull @Min(1) @Max(32767) Integer maximoPaquetes) {
        public Capacidad datos() { return new Capacidad(capacidadPesoKg, capacidadVolumenM3, maximoPaquetes); }
    }
    public record CambiarEstado(@NotNull EstadoFurgoneta estado) {}
}
