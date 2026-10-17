package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.RespuestasSeguridad;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ModoDemoGestionTests.Sonda.class,
        properties = {"DEMO_SIN_AUTENTICACION=true", "SEGURIDAD_URL="})
@Import({ConfiguracionSeguridad.class, RespuestasSeguridad.class, ModoDemoGestionTests.Sonda.class})
class ModoDemoGestionTests {
    @Autowired MockMvc http;
    @Autowired ApplicationContext contexto;

    @Test
    void inyectaActorDeAuditoriaSinAceptarIdentidadesDelCliente() throws Exception {
        assertThat(contexto.getBeansOfType(JwtDecoder.class)).isEmpty();
        http.perform(get("/api/v1/despachos/demo-identidad").header("Authorization", "Bearer vencido"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("00000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.tipo").value("acceso"));
    }

    @Test
    void conservaLosPermisosTecnicosDeCreacionYCotizacion() throws Exception {
        http.perform(post("/api/v1/despachos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("modulo-ventas"))
                .andExpect(jsonPath("$.tipo").value("servicio"));
        http.perform(post("/api/v1/cotizaciones")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("cotizaciones:calcular"));
    }

    @RestController
    public static class Sonda {
        @GetMapping("/api/v1/despachos/demo-identidad")
        @PreAuthorize("hasRole('GESTOR_DESPACHO')")
        public Map<String, String> consultar(@AuthenticationPrincipal Jwt identidad) {
            return datos(identidad);
        }
        @PostMapping({"/api/v1/despachos", "/api/v1/cotizaciones"})
        public Map<String, String> crear(@AuthenticationPrincipal Jwt identidad) {
            return datos(identidad);
        }
        private Map<String, String> datos(Jwt identidad) {
            return Map.of("sub", identidad.getSubject(), "tipo", identidad.getClaimAsString("tipo"),
                    "scope", identidad.getClaimAsString("scope"));
        }
    }
}
