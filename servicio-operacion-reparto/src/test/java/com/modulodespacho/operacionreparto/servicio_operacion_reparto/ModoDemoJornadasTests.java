package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api.ControladorCapacidadInterna;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.errores.RespuestasSeguridad;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ControladorCapacidadInterna.class,
        properties={"DEMO_SIN_AUTENTICACION=true","SEGURIDAD_URL="})
@Import({ConfiguracionSeguridad.class,RespuestasSeguridad.class})
class ModoDemoJornadasTests {
    private static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Autowired MockMvc http;
    @MockitoBean GestorJornadas gestor;

    @Test void reservaYLiberaConIdentidadTecnicaSinSeguridadExterna() throws Exception {
        when(gestor.reservar(eq(ID),any(),eq("modulo-despacho")))
                .thenReturn(new ConfirmacionReserva(ID,"CONFIRMADA",new Capacidad(BigDecimal.TEN,BigDecimal.ONE,2)));
        String reserva="""
                {"idDespacho":"%s","idRepartidor":"%s","idJornada":"%s",
                 "requerido":{"pesoKg":1,"volumenM3":1,"paquetes":1}}
                """.formatted(ID,ID,ID);
        http.perform(post("/internal/v1/capacidad/reservas").header("Idempotency-Key",ID)
                .contentType("application/json").content(reserva)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
        when(gestor.liberar(ID,ID,"COMPENSACION","modulo-despacho"))
                .thenReturn(new Liberacion(ID,ID,"LIBERADA","COMPENSACION",Instant.now()));
        http.perform(post("/internal/v1/capacidad/reservas/"+ID+"/liberacion").contentType("application/json")
                .content("{\"idDespacho\":\""+ID+"\",\"motivo\":\"COMPENSACION\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("LIBERADA"));
        verify(gestor).reservar(eq(ID),any(),eq("modulo-despacho"));
        verify(gestor).liberar(ID,ID,"COMPENSACION","modulo-despacho");
    }

    @Test void proyectaConScopeDemoPropioYConservaValidaciones() throws Exception {
        String cuerpo="""
                {"idDespacho":"%s","idPedido":"PED-DEMO","codigoRastreoInterno":"TRK-DEMO",
                 "estado":"ASIGNADO","idJornada":"%s","idReserva":"%s","secuenciaRuta":1,
                 "destinatario":{"nombre":"Persona Demo","telefono":"999111222"},
                 "destino":{"direccion":"Direccion de prueba","distrito":"Prueba"},
                 "fechaProgramada":"2026-10-17","numeroIntentos":0,"version":1,"actualizadoEn":"2026-10-17T15:00:00Z"}
                """.formatted(ID,ID,ID);
        http.perform(post("/internal/v1/proyecciones/despachos/"+ID).contentType("application/json").content(cuerpo))
                .andExpect(status().isNoContent());
        verify(gestor).proyectar(eq(ID),any(),eq("modulo-despacho"));
        http.perform(post("/internal/v1/capacidad/reservas").header("Idempotency-Key",ID)
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/internal/v1/ruta-desconocida")).andExpect(status().isForbidden());
    }
}
