package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.time.Instant;
import java.util.UUID;

public record DistritoCatalogoVista(
        UUID id,
        String nombre,
        String provincia,
        String departamento,
        String ubigeo,
        EstadoDistrito estado,
        long versionDistrito,
        boolean catalogoBase,
        boolean identidadEditable,
        Instant creadoEn,
        Instant actualizadoEn) {}
