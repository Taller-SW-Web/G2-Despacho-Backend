package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ControladorSeguimientoPedido.class, properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@Import({ConsultarSeguimientoPedido.class, ConfiguracionSeguridad.class, RespuestasSeguridad.class,
        FiltroCorrelacion.class, ManejadorErroresApi.class})
class ControladorSeguimientoPedidoTests {
    private static final String RUTA = "/api/v1/seguimientos/pedidos/{idPedido}";
    @Autowired MockMvc http;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean LectorSeguimientoPedido lector;

    @Test void autenticaYAutorizaAntesDeConsultarLaExistenciaDelPedido() throws Exception {
        when(decodificador.decode("invalido")).thenThrow(new BadJwtException("Token inválido"));
        when(decodificador.decode("sin-scope")).thenReturn(token("servicio", "cotizaciones:calcular"));
        when(decodificador.decode("humano")).thenReturn(token("acceso", "seguimientos:leer"));
        for (String pedido : List.of("PED-EXISTENTE", "PED-INEXISTENTE")) {
            http.perform(get(RUTA, pedido)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("DESP_ERROR_TOKEN_INVALIDO"));
            http.perform(get(RUTA, pedido).header("Authorization", "Bearer invalido"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
            for (String credencial : List.of("sin-scope", "humano")) {
                http.perform(get(RUTA, pedido).header("Authorization", "Bearer " + credencial))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.idPedido").doesNotExist());
            }
        }
        verifyNoInteractions(lector);
    }

    @Test void conservaElIdentificadorOpacoYDevuelveLaProyeccionSinCache() throws Exception {
        when(decodificador.decode("canal")).thenReturn(token("servicio", "seguimientos:leer"));
        String pedido = "ped-000ABC";
        Instant fecha = Instant.parse("2026-11-06T17:00:00Z");
        when(lector.consultar(pedido)).thenReturn(new SeguimientoPedido(pedido, EstadoDespacho.EN_CAMINO,
                "En camino", LocalDate.parse("2026-11-07"), "San Borja",
                List.of(new SeguimientoPedido.Hito(EstadoDespacho.EN_CAMINO, "En camino", fecha)), fecha));
        http.perform(get(RUTA, pedido).header("Authorization", "Bearer canal"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.idPedido").value(pedido)).andExpect(jsonPath("$.estado").value("EN_CAMINO"))
                .andExpect(jsonPath("$.fechaProgramada").value("2026-11-07"))
                .andExpect(jsonPath("$.hitos[0].fecha").value("2026-11-06T17:00:00Z"));
        verify(lector).consultar(pedido);
    }

    @Test void rechazaIdentificadoresInvalidosSinNormalizarlosNiConsultarLaBase() throws Exception {
        when(decodificador.decode("canal")).thenReturn(token("servicio", "seguimientos:leer"));
        for (String pedido : List.of(" ", " PED-1", "PED-1 ", "P".repeat(129))) {
            http.perform(get(RUTA, pedido).header("Authorization", "Bearer canal"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_SOLICITUD_INVALIDA"));
        }
        verifyNoInteractions(lector);
    }

    @Test void pedidoSinDespachoDevuelveProblemaPublicoConCorrelacion() throws Exception {
        when(decodificador.decode("canal")).thenReturn(token("servicio", "seguimientos:leer"));
        when(lector.consultar("PED-SIN-DESPACHO")).thenThrow(ErrorDeNegocio.de(404,
                "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El pedido no tiene un despacho disponible"));
        http.perform(get(RUTA, "PED-SIN-DESPACHO").header("Authorization", "Bearer canal"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private Jwt token(String tipo, String scope) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject("modulo-marketplace")
                .claim("tipo", tipo).claim("scope", scope).claim("roles", List.of("GESTOR_DESPACHO"))
                .audience(List.of("api-despacho")).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
