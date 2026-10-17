package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DetalleIncidenciaJdbcTests {
    @Test
    void exponeSoloDatosHistoricosPermitidosSinPayloadsInternosNiReconstruccionDelUltimoFallo() {
        var lector = new DetalleIncidenciaJdbc(mock(JdbcClient.class), JsonMapper.builder().build());
        var datos = lector.datosPresentacion("""
                {"idIncidencia":"incidencia-anterior","selloIntacto":false,"observacionRecepcion":null,
                 "payload":{"token":"dato-no-publicable"},"numeroIntentos":99}
                """, "RECEPCION_EN_CENTRO", "FALLIDO");
        assertThat(datos).containsEntry("idIncidencia", "incidencia-anterior").containsEntry("selloIntacto", false)
                .containsEntry("observacionRecepcion", null).doesNotContainKeys("payload", "numeroIntentos");
        assertThat(lector.datosPresentacion("{}", "TRANSICION", "FALLIDO")).isEmpty();
        assertThatThrownBy(() -> lector.datosPresentacion("{\"selloIntacto\":\"false\"}", "RECEPCION_EN_CENTRO", "FALLIDO"))
                .isInstanceOf(com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException.class);
    }
}
