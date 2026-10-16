package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.api;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.DistritoCatalogoVista;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionDistritos;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/distritos")
public class ControladorDistritos {

    private final GestionDistritos gestionDistritos;

    public ControladorDistritos(GestionDistritos gestionDistritos) {
        this.gestionDistritos = gestionDistritos;
    }

    @GetMapping
    @Operation(summary = "Listar el catálogo de distritos")
    public List<DistritoCatalogoVista> listar(@RequestParam(required = false) String nombre) {
        return gestionDistritos.catalogo(nombre);
    }
}
