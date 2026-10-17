package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ConsultaZonaHttpTests {
    @Test void confirmaIdentificadorYEstadoConElTokenHumanoSinCompartirPersistencia() {
        UUID zona=UUID.randomUUID();var constructor=RestClient.builder().baseUrl("https://gestion.example");
        var servidor=MockRestServiceServer.bindTo(constructor).build();
        servidor.expect(requestTo("https://gestion.example/api/v1/zonas/"+zona)).andExpect(header("Authorization","Bearer prueba"))
                .andRespond(withSuccess("{\"id\":\""+zona+"\",\"estado\":\"ACTIVO\"}",MediaType.APPLICATION_JSON));
        new ConsultaZonaHttp(constructor.build()).verificar(zona,"prueba");servidor.verify();
    }
    @Test void unaZonaInactivaONoExistenteEsUnRechazoNoUnaApertura() {
        for (boolean ausente:new boolean[]{true,false}) {
            UUID zona=UUID.randomUUID();var constructor=RestClient.builder().baseUrl("https://gestion.example");
            var servidor=MockRestServiceServer.bindTo(constructor).build();
            servidor.expect(anything()).andRespond(ausente?withStatus(HttpStatus.NOT_FOUND):withSuccess("{\"id\":\""+zona+"\",\"estado\":\"INACTIVO\"}",MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> new ConsultaZonaHttp(constructor.build()).verificar(zona,"prueba"))
                    .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(422));
        }
    }
    @Test void caidaRespuestaAjenaYEnumDesconocidoNoHabilitanZona() {
        for (String cuerpo:new String[]{"{}","{\"id\":\""+UUID.randomUUID()+"\",\"estado\":\"ACTIVO\"}","{\"id\":\"%s\",\"estado\":\"OTRO\"}"}) {
            UUID zona=UUID.randomUUID();var constructor=RestClient.builder().baseUrl("https://gestion.example");
            var servidor=MockRestServiceServer.bindTo(constructor).build();
            servidor.expect(anything()).andRespond(withSuccess(cuerpo.replace("%s",zona.toString()),MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> new ConsultaZonaHttp(constructor.build()).verificar(zona,"prueba"))
                    .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(503));
        }
    }
}
