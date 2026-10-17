package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.api;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.errores.RespuestasSeguridad;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizarLecturaEvidencia;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ControladorLecturaEvidencia.class, properties = "SEGURIDAD_URL=http://localhost:9999")
@Import({ConfiguracionSeguridad.class, RespuestasSeguridad.class})
class ControladorLecturaEvidenciaTests {
    @Autowired MockMvc http;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean AutorizarLecturaEvidencia autorizacion;

    @Test
    void exigeIdentidadHumanaYNoStoreYRechazaCuerpoOParametros() throws Exception {
        String ruta = "/api/v1/evidencias/foto/acceso";
        when(decodificador.decode("gestor")).thenReturn(token("acceso"));
        when(decodificador.decode("servicio")).thenReturn(token("servicio"));
        http.perform(post(ruta)).andExpect(status().isUnauthorized());
        http.perform(post(ruta).header("Authorization", "Bearer servicio")).andExpect(status().isForbidden());
        verifyNoInteractions(autorizacion);
        http.perform(post(ruta).header("Authorization", "Bearer gestor").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_CONSULTA_INVALIDA"));
        when(autorizacion.autorizar("foto", "gestor", true)).thenReturn(new AutorizarLecturaEvidencia.AccesoEvidencia(
                "foto", "despacho", "https://storage.example/objeto-sintetico", Instant.now().plusSeconds(120)));
        http.perform(post(ruta).header("Authorization", "Bearer gestor"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.idDespacho").value("despacho"));
    }
    private Jwt token(String tipo) {
        return Jwt.withTokenValue("sintetico").header("alg", "RS256").subject("gestor")
                .claim("tipo", tipo).claim("roles", List.of("GESTOR_DESPACHO")).build();
    }
}
