package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecodificadorJwtPorAmbitoTests {
    private final JwtDecoder firma = mock(JwtDecoder.class);
    private final DecodificadorJwtPorAmbito decodificador =
            new DecodificadorJwtPorAmbito(firma, new ValidadorTokenDespacho("api-despacho"));

    @Test void internoAdmiteAudienciasActualesDeG7SinAmpliarElAccesoPublico() {
        when(firma.decode("tecnico")).thenReturn(token(List.of("api-productos", "api-seguridad", "api-ventas")));
        assertThat(autenticar("/internal/v1/capacidad/reservas").getName()).isEqualTo("modulo-despacho");
        assertThatThrownBy(() -> autenticar("/api/v1/seguimientos/pedidos/PED-1"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> decodificador.decode("tecnico")).isInstanceOf(BadJwtException.class);
    }

    @Test void internoAdmiteAudienciaAusenteYLaRutaPublicaSigueExigiendola() {
        when(firma.decode("tecnico")).thenReturn(token(List.of()));
        assertThat(autenticar("/internal/v1/catalogos/motivos-fallo").isAuthenticated()).isTrue();
        for (String ruta : List.of("/api/v1/cotizaciones", "/api/v1/internal/v1/prueba", "/internal/v2/prueba")) {
            assertThatThrownBy(() -> autenticar(ruta)).as(ruta).isInstanceOf(OAuth2AuthenticationException.class);
        }
    }

    @Test void elContextoDeLaAplicacionNoCambiaElAmbito() {
        when(firma.decode("tecnico")).thenReturn(token(List.of()));
        var solicitud = new MockHttpServletRequest("POST", "/despacho/internal/v1/capacidad/reservas");
        solicitud.setContextPath("/despacho");
        var resolver = DecodificadorJwtPorAmbito.resolver(decodificador, new JwtAuthenticationConverter());
        assertThat(resolver.resolve(solicitud).authenticate(new BearerTokenAuthenticationToken("tecnico")).isAuthenticated()).isTrue();
    }

    @Test void ningunaRutaEvitaLaVerificacionCriptografica() {
        when(firma.decode("tecnico")).thenThrow(new BadJwtException("Firma no aceptada"));
        for (String ruta : List.of("/internal/v1/capacidad/reservas", "/api/v1/cotizaciones")) {
            assertThatThrownBy(() -> autenticar(ruta)).isInstanceOf(OAuth2AuthenticationException.class);
        }
    }

    @Test void publicoConAudienciaCorrectaConservaLaAutenticacion() {
        when(firma.decode("tecnico")).thenReturn(token(List.of("api-despacho")));
        assertThat(autenticar("/api/v1/seguimientos/pedidos/PED-1").isAuthenticated()).isTrue();
    }

    private org.springframework.security.core.Authentication autenticar(String ruta) {
        var resolver = DecodificadorJwtPorAmbito.resolver(decodificador, new JwtAuthenticationConverter());
        return resolver.resolve(new MockHttpServletRequest("POST", ruta))
                .authenticate(new BearerTokenAuthenticationToken("tecnico"));
    }

    private Jwt token(List<String> audiencias) {
        return Jwt.withTokenValue("tecnico").header("alg", "RS256").subject("modulo-despacho")
                .claim("tipo", "servicio").claim("scope", "seguimientos:leer").claim("jti", "prueba")
                .audience(audiencias).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
