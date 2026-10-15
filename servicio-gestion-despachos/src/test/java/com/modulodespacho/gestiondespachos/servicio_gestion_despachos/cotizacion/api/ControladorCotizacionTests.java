package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.api;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.CalcularCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure.AdaptadorCotizacionSimulada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.RespuestasSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ControladorCotizacion.class, properties = "SEGURIDAD_URL=http://localhost:9999")
@ActiveProfiles("integracion-local")
@Import({ConfiguracionSeguridad.class, RespuestasSeguridad.class, FiltroCorrelacion.class,
        CalcularCotizacion.class, AdaptadorCotizacionSimulada.class})
class ControladorCotizacionTests {

    @Autowired
    MockMvc cliente;

    @MockitoBean
    JwtDecoder decodificadorJwt;

    @BeforeEach
    void prepararTokens() {
        when(decodificadorJwt.decode("servicio")).thenReturn(token("servicio", "cotizaciones:calcular"));
        when(decodificadorJwt.decode("sin-scope")).thenReturn(token("servicio", "seguimientos:leer"));
        when(decodificadorJwt.decode("humano")).thenReturn(token("acceso", "cotizaciones:calcular"));
        when(decodificadorJwt.decode("invalido")).thenThrow(new BadJwtException("Token inválido"));
    }

    private Jwt token(String tipo, String scope) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject("modulo-chatbot")
                .claim("tipo", tipo).claim("scope", scope)
                .audience(java.util.List.of("api-despacho"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    void cotizaConProductosDelCatalogoSimulado() throws Exception {
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                .contentType("application/json").content("""
                        {"destino":{"distrito":"San Borja"},"lineas":[
                            {"sku":"POL-NEG-M","cantidad":5},{"sku":"ZAP-RUN-42","cantidad":1}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoCotizacion").value("EXACTA"))
                .andExpect(jsonPath("$.pesoTotalKg").value(2.65))
                .andExpect(jsonPath("$.volumenTotalM3").value(0.021))
                .andExpect(jsonPath("$.costoEnvio").value(10.0))
                .andExpect(jsonPath("$.fechaEstimadaEntrega").exists())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void consultaSoloCoberturaSinCostoNiPlazo() throws Exception {
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                .contentType("application/json").content("{\"destino\":{\"distrito\":\"San Borja\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(true))
                .andExpect(jsonPath("$.costoEnvio").doesNotExist())
                .andExpect(jsonPath("$.plazoEstimadoDiasHabiles").doesNotExist());
    }

    @Test
    void sinCoberturaNoValidaLineas() throws Exception {
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                .contentType("application/json")
                .content("{\"destino\":{\"distrito\":\"Fuera de cobertura\"},\"lineas\":[{\"cantidad\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(false))
                .andExpect(jsonPath("$.costoEnvio").doesNotExist());
    }

    @Test
    void distingueDestinoAusenteLineasVaciasYCantidadInvalida() throws Exception {
        String[] cuerpos = {"{}", "{\"destino\":{\"distrito\":\"San Borja\"},\"lineas\":[]}",
                "{\"destino\":{\"distrito\":\"San Borja\"},\"lineas\":[{\"sku\":\"POL-NEG-M\",\"cantidad\":0}]}"};
        String[] codigos = {"DESP_ERROR_DESTINO_REQUERIDO", "DESP_ERROR_LINEAS_VACIAS", "DESP_ERROR_CANTIDAD_INVALIDA"};
        for (int indice = 0; indice < cuerpos.length; indice++) {
            cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                    .contentType("application/json").content(cuerpos[indice]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value(codigos[indice]));
        }
    }

    @Test
    void exigeTokenDeServicioConScope() throws Exception {
        cliente.perform(post("/api/v1/cotizaciones")).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer invalido"))
                .andExpect(status().isUnauthorized());
        for (String token : java.util.List.of("sin-scope", "humano")) {
            cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_SCOPE_INSUFICIENTE"));
        }
    }

    @Test
    void rechazaJsonInvalido() throws Exception {
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_SOLICITUD_INVALIDA"));
    }
}
