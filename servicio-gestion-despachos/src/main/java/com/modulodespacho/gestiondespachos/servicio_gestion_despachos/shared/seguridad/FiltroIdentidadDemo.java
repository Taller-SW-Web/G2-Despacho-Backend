package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Identidad interna de demostración; solo se instala al activar explícitamente el modo demo. */
final class FiltroIdentidadDemo extends OncePerRequestFilter {
    private final String usuario;

    FiltroIdentidadDemo(String usuario) {
        this.usuario = UUID.fromString(usuario).toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta,
            FilterChain cadena) throws ServletException, IOException {
        String ruta = solicitud.getRequestURI().substring(solicitud.getContextPath().length());
        String sujeto = usuario;
        String tipo = "acceso";
        String scope = "";
        if ("POST".equals(solicitud.getMethod()) && ruta.equals("/api/v1/cotizaciones")) {
            sujeto = "modulo-demo";
            tipo = "servicio";
            scope = "cotizaciones:calcular";
        } else if ("POST".equals(solicitud.getMethod()) && (ruta.equals("/api/v1/despachos")
                || ruta.matches("/api/v1/despachos/pedidos/[^/]+/cancelacion"))) {
            sujeto = "modulo-ventas";
            tipo = "servicio";
            scope = ruta.equals("/api/v1/despachos") ? "despachos:crear" : "despachos:cancelar";
        } else if ("GET".equals(solicitud.getMethod()) && ruta.startsWith("/api/v1/seguimientos/")) {
            sujeto = "modulo-demo";
            tipo = "servicio";
            scope = "seguimientos:leer";
        }
        Instant ahora = Instant.now();
        Jwt identidad = Jwt.withTokenValue("identidad-demo-interna").header("alg", "none")
                .subject(sujeto).issuedAt(ahora).expiresAt(ahora.plusSeconds(300))
                .claim("tipo", tipo).claim("roles", List.of("GESTOR_DESPACHO"))
                .claim("scope", scope).claim("demo", true).build();
        var permisos = List.of(new SimpleGrantedAuthority(
                tipo.equals("servicio") ? "SCOPE_" + scope : "ROLE_GESTOR_DESPACHO"));
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new JwtAuthenticationToken(identidad, permisos));
        SecurityContextHolder.setContext(contexto);
        try {
            cadena.doFilter(solicitud, respuesta);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
