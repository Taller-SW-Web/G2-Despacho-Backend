package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.DistritoCatalogoVista;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionDistritos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/distritos")
public class ControladorDistritos {

    private final GestionDistritos gestionDistritos;
    private final SolicitudesDistritos solicitudes;

    public ControladorDistritos(
            GestionDistritos gestionDistritos, SolicitudesDistritos solicitudes) {
        this.gestionDistritos = gestionDistritos;
        this.solicitudes = solicitudes;
    }

    @GetMapping
    @Operation(summary = "Listar el catálogo de distritos")
    public List<DistritoCatalogoVista> listar(
            @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of("nombre", "estado"));
        return gestionDistritos.catalogo(
                parametros.getFirst("nombre"),
                solicitudes.filtroEstado(parametros.getFirst("estado")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un distrito y sus restricciones de edición")
    public DistritoCatalogoVista obtener(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of());
        return gestionDistritos.obtener(id);
    }

    @PostMapping
    @Operation(summary = "Registrar un distrito activo")
    public ResponseEntity<DistritoCatalogoVista> crear(
            @RequestBody JsonNode cuerpo, @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of());
        var vista = gestionDistritos.crear(solicitudes.datos(cuerpo, false));
        return ResponseEntity.created(URI.create("/api/v1/distritos/" + vista.id())).body(vista);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Editar los datos permitidos de un distrito")
    public DistritoCatalogoVista editar(
            @PathVariable UUID id,
            @RequestBody JsonNode cuerpo,
            @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of());
        return gestionDistritos.editar(id, solicitudes.datos(cuerpo, true));
    }

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Activar o desactivar un distrito")
    public DistritoCatalogoVista cambiarEstado(
            @PathVariable UUID id,
            @RequestBody JsonNode cuerpo,
            @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of());
        var solicitud = solicitudes.estado(cuerpo);
        return gestionDistritos.cambiarEstado(id, solicitud.estado(), solicitud.versionDistrito());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Dar de baja lógicamente un distrito")
    public DistritoCatalogoVista desactivar(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> parametros) {
        solicitudes.parametros(parametros, Set.of("versionDistrito"));
        return gestionDistritos.cambiarEstado(
                id,
                EstadoDistrito.INACTIVO,
                solicitudes.versionParametro(parametros.getFirst("versionDistrito")));
    }
}
