package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.api;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.ConsultarSeguimientoPedido;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.SeguimientoPedido;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!integracion-local")
public class ControladorSeguimientoPedido {
    private final ConsultarSeguimientoPedido consulta;

    public ControladorSeguimientoPedido(ConsultarSeguimientoPedido consulta) {
        this.consulta = consulta;
    }

    @GetMapping("/api/v1/seguimientos/pedidos/{idPedido}")
    @Operation(
            summary = "Consultar el seguimiento público de un pedido",
            description =
                    "Requiere tipo=servicio, audiencia api-despacho y scope seguimientos:leer.")
    public ResponseEntity<SeguimientoPedido> consultar(@PathVariable String idPedido) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(consulta.consultar(idPedido));
    }
}
