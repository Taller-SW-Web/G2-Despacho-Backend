package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OperacionIncidenciasDemoTests {
    @Test
    void consultaOperacionSinSolicitarNiEnviarCredencialesEnModoDemo() {
        var constructor = RestClient.builder().baseUrl("https://operacion.example");
        var servidor = MockRestServiceServer.bindTo(constructor).build();
        servidor.expect(requestTo("https://operacion.example/internal/v1/operacion/consulta-incidencias"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                {"repartidores":[{"idRepartidor":"rep-1","nombre":"Persona demo"}],
                 "jornadas":[],"consultadoEn":"2026-10-17T12:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        var lector = new OperacionIncidenciasHttp(constructor.build(), () -> "", true);
        assertThat(lector.consultar(List.of("rep-1"), List.of()).repartidores().get("rep-1").nombre())
                .isEqualTo("Persona demo");
        servidor.verify();
    }
}
