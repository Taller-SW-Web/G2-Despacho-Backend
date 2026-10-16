package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ProveedorTokenOperacionTests {

    @Test
    void obtieneReutilizaRenuevaYDescartaCredencialSiFallaLaEmision() {
        RestClient.Builder cliente = RestClient.builder().baseUrl("http://seguridad-prueba");
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(cliente).build();
        for (String firma : new String[] {"primera", "renovada"}) {
            servidor.expect(requestTo("http://seguridad-prueba/api/v1/auth/token"))
                    .andExpect(content().string(containsString("grant_type=client_credentials")))
                    .andExpect(content().string(containsString("client_secret=secreto-prueba")))
                    .andRespond(withSuccess("{\"accessToken\":\"cabecera.cuerpo." + firma
                            + "\",\"tokenType\":\"Bearer\",\"expiresIn\":100,\"scopes\":[\"operacion:incidencias:leer\"]}", MediaType.APPLICATION_JSON));
        }
        servidor.expect(requestTo("http://seguridad-prueba/api/v1/auth/token")).andRespond(withServiceUnavailable());
        Clock reloj = mock(Clock.class);
        Instant inicio = Instant.parse("2026-10-16T18:00:00Z");
        when(reloj.instant()).thenReturn(inicio, inicio.plusSeconds(5), inicio.plusSeconds(80), inicio.plusSeconds(160));
        AtomicReference<String> externo = new AtomicReference<>("");
        ProveedorTokenOperacion proveedor = new ProveedorTokenOperacion(cliente.build(), "modulo-despacho",
                () -> "secreto-prueba", externo::get, reloj);
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.primera");
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.primera");
        assertThat(proveedor.get()).isEqualTo("cabecera.cuerpo.renovada");
        assertThat(proveedor.get()).isEmpty();
        externo.set("credencial-externa-de-prueba");
        assertThat(proveedor.get()).isEqualTo("credencial-externa-de-prueba");
        servidor.verify();
    }
}
