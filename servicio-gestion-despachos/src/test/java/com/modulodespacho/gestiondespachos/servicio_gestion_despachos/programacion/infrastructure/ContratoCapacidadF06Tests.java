package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

/** Captura producida por JornadasPostgresTests con PostgreSQL real y datos sintéticos. */
class ContratoCapacidadF06Tests {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void clienteF02ConsumeConfirmacionYLiberacionDelProveedorF06(boolean modoDemo) throws Exception {
        var json=JsonMapper.builder().build();
        var captura=json.readTree(getClass().getResourceAsStream("/contratos/f06-respuestas-backend.json"));
        String reserva=captura.path("reserva").path("idReserva").asText();
        UUID despacho=UUID.fromString(captura.path("liberacion").path("idDespacho").asText());
        var jornada=captura.path("detalle");
        UUID clave=UUID.randomUUID();
        var constructor=RestClient.builder().baseUrl("http://operacion-prueba");
        var servidor=MockRestServiceServer.bindTo(constructor).build();
        servidor.expect(requestTo("http://operacion-prueba/internal/v1/capacidad/reservas"))
                .andExpect(modoDemo ? headerDoesNotExist("Authorization") : header("Authorization","Bearer token-prueba"))
                .andExpect(header("Idempotency-Key",clave.toString()))
                .andRespond(withSuccess(json.writeValueAsString(captura.path("reserva")),MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("http://operacion-prueba/internal/v1/capacidad/reservas/"+reserva+"/liberacion"))
                .andExpect(modoDemo ? headerDoesNotExist("Authorization") : header("Authorization","Bearer token-prueba"))
                .andRespond(withSuccess(json.writeValueAsString(captura.path("liberacion")),MediaType.APPLICATION_JSON));
        var cliente=new CapacidadProgramacionHttp(constructor.build(),()-> {
            if(modoDemo) throw new AssertionError("La demo no debe solicitar credenciales");
            return "token-prueba";
        },modoDemo);
        var intencion=new IntencionAsignacion(clave,despacho,1,"gestor-prueba",
                jornada.path("idRepartidor").asText(),jornada.path("idJornada").asText(),null,"PENDIENTE",null,null,
                new Carga(new BigDecimal("20"),BigDecimal.ONE,2));
        var confirmacion=cliente.reservar(intencion);
        assertThat(confirmacion.idReserva()).isEqualTo(reserva);
        assertThat(confirmacion.capacidadRemanente().pesoKg()).isEqualByComparingTo("480");
        assertThat(confirmacion.capacidadRemanente().volumenM3()).isEqualByComparingTo("3");
        assertThat(confirmacion.capacidadRemanente().paquetes()).isEqualTo(78);
        cliente.compensar(reserva,despacho.toString());
        servidor.verify();
    }
}
