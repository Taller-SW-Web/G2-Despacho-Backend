package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ModelosRuta {
    private ModelosRuta() {}
    public record Parada(UUID idDespacho, String codigoOperativo, String codigoRastreoInterno, String estado,
            int posicion, int version, String destinatario, String direccion, BigDecimal pesoKg,
            BigDecimal volumenM3, int cantidadPaquetes, Instant actualizadoEn) {}
    public record Ruta(String idJornada, String estadoJornada, List<Parada> despachos, String versionRuta, Instant consultadoEn) {}
    public record Posicion(@NotNull UUID idDespacho, @NotNull @Min(1) @Max(32767) Integer posicion) {}
    public record Secuencia(@NotNull @Size(max=1000) List<@NotNull @Valid Posicion> despachos) {}
}
