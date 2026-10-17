package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.api;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.ConsultarDetalleIncidencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ControladorDetalleIncidencia.class, properties = "SEGURIDAD_URL=http://localhost:9999")
@Import({ConfiguracionSeguridad.class, RespuestasSeguridad.class, FiltroCorrelacion.class, ManejadorErroresApi.class})
class ControladorDetalleIncidenciaTests {
    @Autowired MockMvc http;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean ConsultarDetalleIncidencia consulta;

    @Test
    void autorizaAntesDeConsultarYNormalizaParametrosInexistenciaEInconsistencia() throws Exception {
        String ruta = "/api/v1/despachos/despacho/incidencia";
        when(decodificador.decode("gestor")).thenReturn(token("GESTOR_DESPACHO"));
        when(decodificador.decode("otro")).thenReturn(token("OTRO"));
        http.perform(get(ruta)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        http.perform(get(ruta).header("Authorization", "Bearer otro")).andExpect(status().isForbidden());
        verifyNoInteractions(consulta);
        http.perform(get(ruta).header("Authorization", "Bearer gestor").queryParam("pagina", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_CONSULTA_INVALIDA"));
        when(consulta.consultar("despacho")).thenThrow(ErrorDeNegocio.de(404, "DESP_ERROR_INCIDENCIA_NO_ENCONTRADA", "Sin incidencia"));
        http.perform(get(ruta).header("Authorization", "Bearer gestor"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DESP_ERROR_INCIDENCIA_NO_ENCONTRADA"));
        doThrow(new com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException("Referencia rota")).when(consulta).consultar("despacho");
        http.perform(get(ruta).header("Authorization", "Bearer gestor"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DESP_ERROR_DATOS_INCONSISTENTES"));
    }

    private Jwt token(String rol) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject("gestor-prueba")
                .claim("tipo", "acceso").claim("roles", List.of(rol)).build();
    }
}
