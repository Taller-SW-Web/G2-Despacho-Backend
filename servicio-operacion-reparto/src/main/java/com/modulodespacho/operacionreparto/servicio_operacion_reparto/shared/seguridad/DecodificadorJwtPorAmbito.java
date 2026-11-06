package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/** Conserva audiencia pública; las rutas internas verifican la identidad propia mediante su autorización. */
public final class DecodificadorJwtPorAmbito implements JwtDecoder {
    private final JwtDecoder firmaEIdentidad;
    private final OAuth2TokenValidator<Jwt> validacionPublica;

    public DecodificadorJwtPorAmbito(JwtDecoder firmaEIdentidad, OAuth2TokenValidator<Jwt> validacionPublica) {
        this.firmaEIdentidad = firmaEIdentidad;
        this.validacionPublica = validacionPublica;
    }

    @Override
    public Jwt decode(String token) {
        Jwt identidad = firmaEIdentidad.decode(token);
        if (validacionPublica.validate(identidad).hasErrors()) {
            throw new BadJwtException("El token no cumple el contrato público de Despacho");
        }
        return identidad;
    }

    private Jwt decodificarInterno(String token) {
        // El decodificador base siempre comprueba firma, emisor, vigencia y estructura de identidad.
        return firmaEIdentidad.decode(token);
    }

    public static AuthenticationManagerResolver<HttpServletRequest> resolver(
            JwtDecoder decodificador, JwtAuthenticationConverter convertidor) {
        AuthenticationManager publico = proveedor(decodificador, convertidor);
        JwtDecoder interno = decodificador instanceof DecodificadorJwtPorAmbito porAmbito
                ? porAmbito::decodificarInterno : decodificador;
        AuthenticationManager privado = proveedor(interno, convertidor);
        return solicitud -> {
            String ruta = solicitud.getRequestURI().substring(solicitud.getContextPath().length());
            return ruta.startsWith("/internal/v1/") ? privado : publico;
        };
    }

    private static AuthenticationManager proveedor(JwtDecoder decodificador, JwtAuthenticationConverter convertidor) {
        JwtAuthenticationProvider proveedor = new JwtAuthenticationProvider(decodificador);
        proveedor.setJwtAuthenticationConverter(convertidor);
        return new ProviderManager(proveedor);
    }
}
