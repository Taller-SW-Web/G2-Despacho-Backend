package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure.AutorizadorCargaSupabase;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AutorizadorCargaSupabaseTests {
    private static final UUID DESPACHO = UUID.fromString("b211d92d-baa4-4cea-a81d-c27f9a7e0192");
    private static final UUID EVIDENCIA = UUID.fromString("dc72c0a5-4479-4b44-a603-79f9d6683c2e");
    private static final String URL = "https://proyecto.supabase.co/storage/v1/object/upload/sign/"
            + "evidencias/evidencias/" + DESPACHO + "/" + EVIDENCIA + "?token=firma";

    @Test
    void solicitaUrlFirmadaYNoExponeLaClaveComoParteDeLaRespuesta() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("https://proyecto.supabase.co/storage/v1/object/upload/sign/"
                        + "evidencias/evidencias/" + DESPACHO + "/" + EVIDENCIA))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "clave-de-servicio"))
                .andExpect(header("Authorization", "Bearer clave-de-servicio"))
                .andRespond(withSuccess("""
                        {"url":"/object/upload/sign/evidencias/evidencias/%s/%s?token=firma"}
                        """.formatted(DESPACHO, EVIDENCIA), MediaType.APPLICATION_JSON));

        AutorizadorCargaSupabase autorizador = new AutorizadorCargaSupabase(builder,
                politica("https://proyecto.supabase.co", "clave-de-servicio"));
        Instant antes = Instant.now();
        var resultado = autorizador.autorizar(DESPACHO, EVIDENCIA);

        assertThat(resultado.urlCarga()).isEqualTo(URL);
        assertThat(resultado.urlCarga()).doesNotContain("clave-de-servicio");
        assertThat(resultado.venceEn()).isBetween(antes.plus(Duration.ofHours(2)).minusSeconds(2),
                antes.plus(Duration.ofHours(2)).plusSeconds(2));
        servidor.verify();
    }

    @Test
    void rechazaUnaUrlFirmadaDeUnOrigenDistinto() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("https://proyecto.supabase.co/storage/v1/object/upload/sign/"
                        + "evidencias/evidencias/" + DESPACHO + "/" + EVIDENCIA))
                .andRespond(withSuccess("""
                        {"url":"https://malicioso.example/object/upload/sign/evidencias/%s/%s?token=externo"}
                        """.formatted(DESPACHO, EVIDENCIA), MediaType.APPLICATION_JSON));
        AutorizadorCargaSupabase autorizador = new AutorizadorCargaSupabase(builder,
                politica("https://proyecto.supabase.co", "clave-de-servicio"));

        assertThatThrownBy(() -> autorizador.autorizar(DESPACHO, EVIDENCIA))
                .isInstanceOf(EvidenciaNoDisponible.class);
        servidor.verify();
    }

    @Test
    void informaIndisponibilidadDelAlmacenamientoSinDarUrlDeExito() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        servidor.expect(requestTo("https://proyecto.supabase.co/storage/v1/object/upload/sign/"
                        + "evidencias/evidencias/" + DESPACHO + "/" + EVIDENCIA))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        AutorizadorCargaSupabase autorizador = new AutorizadorCargaSupabase(builder,
                politica("https://proyecto.supabase.co", "clave-de-servicio"));

        assertThatThrownBy(() -> autorizador.autorizar(DESPACHO, EVIDENCIA))
                .isInstanceOf(EvidenciaNoDisponible.class);
        servidor.verify();
    }

    private PoliticaCargaEvidencia politica(String url, String clave) {
        return new PoliticaCargaEvidencia("image/jpeg", 2_000_000, url, clave, "evidencias");
    }
}
