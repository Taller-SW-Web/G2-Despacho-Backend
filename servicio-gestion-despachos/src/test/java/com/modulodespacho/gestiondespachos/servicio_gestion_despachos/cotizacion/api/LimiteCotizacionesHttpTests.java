package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ServicioCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure.FiltroLimiteCotizaciones;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.RespuestasSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = ControladorCotizacionReal.class,
        properties = {
            "SEGURIDAD_URL=http://localhost:9999",
            "despacho.cotizacion.limite-por-minuto=2"
        })
@Import({
    ConfiguracionSeguridad.class,
    RespuestasSeguridad.class,
    FiltroCorrelacion.class,
    FiltroLimiteCotizaciones.class
})
class LimiteCotizacionesHttpTests {
    @Autowired MockMvc http;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean ServicioCotizacion cotizador;

    @BeforeEach
    void preparar() {
        for (String origen : List.of("a", "b", "sin-scope")) {
            when(decodificador.decode(origen))
                    .thenReturn(
                            Jwt.withTokenValue(origen)
                                    .header("alg", "RS256")
                                    .subject("modulo-" + origen)
                                    .claim("tipo", "servicio")
                                    .claim(
                                            "scope",
                                            origen.equals("sin-scope")
                                                    ? "seguimientos:leer"
                                                    : "cotizaciones:calcular")
                                    .audience(List.of("api-despacho"))
                                    .issuedAt(Instant.now())
                                    .expiresAt(Instant.now().plusSeconds(300))
                                    .build());
        }
        when(cotizador.cotizar(any()))
                .thenReturn(
                        new ResultadoCotizacion(
                                false,
                                null,
                                null,
                                null,
                                "Destino",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "Fuera de cobertura"));
    }

    @Test
    void limitaAntesDeCalcularYAislaPorConsumidorEIp() throws Exception {
        for (int i = 0; i < 2; i++) consultar("a", "192.0.2.10", 200);
        consultar("a", "192.0.2.10", 429);
        consultar("b", "192.0.2.10", 200);
        consultar("a", "192.0.2.11", 200);
        verify(cotizador, times(4)).cotizar(any());
    }

    @Test
    void autenticaYAutorizaAntesDeAplicarLimite() throws Exception {
        for (int i = 0; i < 3; i++) consultar("sin-scope", "192.0.2.20", 403);
        http.perform(post("/api/v1/cotizaciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cotizador);
    }

    private void consultar(String token, String ip, int esperado) throws Exception {
        var respuesta =
                http.perform(
                                post("/api/v1/cotizaciones")
                                        .header("Authorization", "Bearer " + token)
                                        .header("X-Forwarded-For", ip)
                                        .contentType("application/json")
                                        .content("{\"destino\":{\"distrito\":\"Destino\"}}"))
                        .andExpect(status().is(esperado));
        if (esperado == 429)
            respuesta.andExpect(jsonPath("$.code").value("DESP_ERROR_LIMITE_COTIZACION"));
    }
}
