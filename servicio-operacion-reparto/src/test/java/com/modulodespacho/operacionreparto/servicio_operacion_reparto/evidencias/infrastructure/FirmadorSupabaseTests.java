package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FirmadorSupabaseTests {
    @Test
    void solicitaFirmaPrivadaConTtlVigenteYTraduceObjetoEliminadoSinExponerCredenciales() {
        var constructor = RestClient.builder();
        var servidor = MockRestServiceServer.bindTo(constructor).build();
        var json = JsonMapper.builder().build();
        var firmador = new FirmadorSupabase("https://storage.example", "privado", "credencial-sintetica", json, constructor.build());
        long vence = Instant.now().plusSeconds(120).getEpochSecond();
        String token = "cabecera." + Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"exp\":" + vence + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".firma";
        servidor.expect(requestTo("https://storage.example/storage/v1/object/sign/privado/salida/foto.png"))
                .andExpect(header("Authorization", "Bearer credencial-sintetica")).andExpect(content().json("{\"expiresIn\":120}"))
                .andRespond(withSuccess(json.writeValueAsString(new FirmadorSupabase.RespuestaFirma("/object/sign/privado/salida/foto.png?token=" + token)), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://storage.example/storage/v1/object/sign/privado/eliminada.png"))
                .andRespond(withResourceNotFound());
        var acceso = firmador.firmar("salida/foto.png", 120);
        assertThat(acceso.venceEn()).isEqualTo(Instant.ofEpochSecond(vence));
        assertThat(java.net.URI.create(acceso.urlLectura()).getHost()).isEqualTo("storage.example");
        assertThatThrownBy(() -> firmador.firmar("eliminada.png", 120)).isInstanceOfSatisfying(ErrorEvidencia.class,
                error -> assertThat(error.codigo()).isEqualTo("DESP_ERROR_EVIDENCIA_NO_ENCONTRADA"));
        assertThatThrownBy(() -> firmador.firmar("../otro-bucket/foto.png", 120)).isInstanceOf(ErrorEvidencia.class);
        servidor.verify();
    }
}
