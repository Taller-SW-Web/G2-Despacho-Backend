package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.PaginaZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.SolicitudEstado;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.SolicitudZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ZonaVista;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/zonas")
public class ControladorZonas {

    private final GestionZonas gestionZonas;

    public ControladorZonas(GestionZonas gestionZonas) {
        this.gestionZonas = gestionZonas;
    }

    @GetMapping
    @Operation(summary = "Listar zonas con filtros y paginación")
    public PaginaZonas listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) String distrito,
            @RequestParam(required = false) EstadoZona estado,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "20") int tamanoPagina) {
        validarPaginacion(pagina, tamanoPagina);
        return gestionZonas.listar(nombre, distrito, estado, pagina, tamanoPagina);
    }

    @PostMapping
    @Operation(summary = "Crear una zona de cobertura")
    public ResponseEntity<ZonaVista> crear(@RequestBody SolicitudZona solicitud) {
        ZonaVista vista = gestionZonas.crear(solicitud);
        return ResponseEntity.created(URI.create("/api/v1/zonas/" + vista.id())).body(vista);
    }

    @GetMapping("/{idZona}")
    @Operation(summary = "Consultar una zona")
    public ZonaVista obtener(@PathVariable UUID idZona) {
        return gestionZonas.obtener(idZona);
    }

    @PutMapping("/{idZona}")
    @Operation(summary = "Editar el nombre o la delimitación de una zona")
    public ZonaVista editar(@PathVariable UUID idZona, @RequestBody SolicitudZona solicitud) {
        return gestionZonas.editar(idZona, solicitud);
    }

    @PatchMapping("/{idZona}/estado")
    @Operation(summary = "Activar o desactivar una zona")
    public ZonaVista cambiarEstado(@PathVariable UUID idZona, @RequestBody SolicitudEstado solicitud) {
        if (solicitud == null || solicitud.estado() == null) {
            throw ErrorDeNegocio.de(400, CodigosError.CAMPOS_INVALIDOS,
                    "La solicitud contiene campos inválidos",
                    List.of(new ErrorDeCampo("estado", "El estado es obligatorio")));
        }
        return gestionZonas.cambiarEstado(idZona, solicitud.estado());
    }

    private void validarPaginacion(int pagina, int tamanoPagina) {
        if (pagina < 1 || tamanoPagina < 1 || tamanoPagina > 100) {
            throw ErrorDeNegocio.de(400, CodigosError.CAMPOS_INVALIDOS,
                    "La paginación es inválida",
                    List.of(new ErrorDeCampo("pagina", "Debe ser mayor o igual que 1"),
                            new ErrorDeCampo("tamanoPagina", "Debe estar entre 1 y 100")));
        }
    }
}
