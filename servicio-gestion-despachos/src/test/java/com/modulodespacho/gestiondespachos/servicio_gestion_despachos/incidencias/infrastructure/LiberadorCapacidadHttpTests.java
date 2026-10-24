package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LiberacionesPendientes;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LiberadorCapacidadHttpTests {
    private final LiberacionesPendientes.Envio envio = new LiberacionesPendientes.Envio(UUID.randomUUID(), UUID.randomUUID(),
            "despacho-1", "incidencia-1", "reserva-original", "jornada-original", 1, false, true);
    @Test void enviaSoloReservaOriginalConCredencialTecnicaYAceptaLiberacionAnterior() {
        var builder = RestClient.builder().baseUrl("https://operacion.test");
        var servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("https://operacion.test/internal/v1/capacidad/reservas/reserva-original/liberacion"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer token-tecnico"))
                .andExpect(content().json("{\"motivo\":\"RECEPCION_EN_CENTRO\",\"idDespacho\":\"despacho-1\"}"))
                .andRespond(withSuccess(respuesta("reserva-original", "despacho-1", "CANCELACION"), MediaType.APPLICATION_JSON));
        var resultado = new LiberadorCapacidadHttp(builder.build(), () -> "token-tecnico", false).liberar(envio);
        assertThat(resultado.confirmada()).isTrue();
        assertThat(resultado.liberadaEn()).isEqualTo("2026-10-24T12:00:00Z");
        servidor.verify();
    }
    @Test void clasificaErroresSinExponerCuerposRemotosYNoAcepta202() {
        for (int codigo : new int[]{202, 302, 400, 401, 403, 404, 409, 429, 500, 503}) {
            var builder = RestClient.builder().baseUrl("https://operacion.test");
            var servidor = MockRestServiceServer.bindTo(builder).build();
            servidor.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(codigo)).body("dato remoto no publicable"));
            var resultado = new LiberadorCapacidadHttp(builder.build(), () -> "tecnico", false).liberar(envio);
            assertThat(resultado.confirmada()).isFalse();
            assertThat(resultado.requiereRevision()).isEqualTo(codigo < 500 && codigo != 429);
            assertThat(resultado.codigo()).isEqualTo("HTTP_" + codigo);
            servidor.verify();
        }
    }
    @Test void reservaAjenaEstadoInvalidoYCuerpoIncompletoNoConfirman() {
        for (String cuerpo : new String[]{respuesta("reserva-nueva", "despacho-1", "RECEPCION_EN_CENTRO"),
                respuesta("reserva-original", "ajeno", "RECEPCION_EN_CENTRO"), "{}",
                respuesta("reserva-original", "despacho-1", "DESCONOCIDO"), "null", "no es json",
                respuesta("reserva-original", "despacho-1", "ENTREGA").replace("LIBERADA", "ACTIVA")}) {
            var builder = RestClient.builder().baseUrl("https://operacion.test");
            var servidor = MockRestServiceServer.bindTo(builder).build();
            servidor.expect(anything()).andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));
            var resultado = new LiberadorCapacidadHttp(builder.build(), () -> "tecnico", false).liberar(envio);
            assertThat(resultado.confirmada()).isFalse();
            assertThat(resultado.requiereRevision()).isTrue();
        }
    }
    @Test void sinCredencialNoInvocaOperacion() {
        var resultado = new LiberadorCapacidadHttp(RestClient.builder().baseUrl("https://no-llamar.test").build(), () -> "", false).liberar(envio);
        assertThat(resultado.requiereRevision()).isTrue();
        assertThat(resultado.codigo()).isEqualTo("CREDENCIAL_NO_DISPONIBLE");
    }
    private String respuesta(String reserva, String despacho, String motivo) {
        return "{\"idReserva\":\"" + reserva + "\",\"idDespacho\":\"" + despacho
                + "\",\"estado\":\"LIBERADA\",\"motivoLiberacion\":\"" + motivo + "\",\"liberadaEn\":\"2026-10-24T12:00:00Z\"}";
    }
}
