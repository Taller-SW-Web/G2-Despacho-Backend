package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ProveedorTokenServicioTests {
    @Test void solicitaScopePropioRenuevaYNoReutilizaCredencialVencidaTrasUnaCaida() {
        var cliente = RestClient.builder().baseUrl("http://seguridad-prueba");
        var servidor = MockRestServiceServer.bindTo(cliente).build();
        for (String firma : new String[]{"primera", "segunda"}) servidor.expect(requestTo("http://seguridad-prueba/api/v1/auth/token"))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_id=modulo-despacho")))
                .andExpect(content().string(containsString("scope=productos%3Afisicos%3Aleer")))
                .andRespond(withSuccess("{\"accessToken\":\"cabecera.cuerpo." + firma
                        + "\",\"tokenType\":\"Bearer\",\"expiresIn\":100,\"scopes\":[\"productos:fisicos:leer\"]}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("http://seguridad-prueba/api/v1/auth/token")).andRespond(withServiceUnavailable());
        Clock reloj = mock(Clock.class);
        Instant inicio = Instant.parse("2026-10-16T18:00:00Z");
        when(reloj.instant()).thenReturn(inicio, inicio.plusSeconds(5), inicio.plusSeconds(80), inicio.plusSeconds(160));
        var proveedor = new ProveedorTokenServicio(cliente.build(), "modulo-despacho", "secreto-prueba", "productos:fisicos:leer", () -> "", reloj);
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.primera");
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.primera");
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.segunda");
        assertThat(proveedor.get()).isEmpty();
        servidor.verify();
    }

    @Test void rechazaScopesInsuficientesTokensMalformadosYVencimientosNoRepresentables() {
        var cliente = RestClient.builder().baseUrl("http://seguridad-prueba");
        var servidor = MockRestServiceServer.bindTo(cliente).build();
        for (String respuesta : new String[]{
                "{\"accessToken\":\"cabecera.cuerpo.firma\",\"tokenType\":\"Bearer\",\"expiresIn\":100,\"scopes\":[\"otro:scope\"]}",
                "{\"accessToken\":\"no-es-jwt\",\"tokenType\":\"Bearer\",\"expiresIn\":100,\"scopes\":[\"ventas:pedidos:estado\"]}",
                "{\"accessToken\":\"cabecera.cuerpo.firma\",\"tokenType\":\"Bearer\",\"expiresIn\":9223372036854775807,\"scopes\":[\"ventas:pedidos:estado\"]}"})
            servidor.expect(requestTo("http://seguridad-prueba/api/v1/auth/token")).andRespond(withSuccess(respuesta, MediaType.APPLICATION_JSON));
        var proveedor = new ProveedorTokenServicio(cliente.build(), "modulo-despacho", "secreto-prueba", "ventas:pedidos:estado", () -> "", Clock.systemUTC());
        for (int intento = 0; intento < 3; intento++) assertThat(proveedor.get()).isEmpty();
        servidor.verify();
    }
}
