package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    protected void doFilterInternal(
            HttpServletRequest solicitud, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String ruta = solicitud.getRequestURI().substring(solicitud.getContextPath().length());
        String sujeto = usuario;
        String tipo = "acceso";
        if ("POST".equals(solicitud.getMethod())
                && ruta.equals("/internal/v1/operacion/consulta-incidencias")) {
            sujeto = "modulo-despacho";
            tipo = "servicio";
        } else if ("POST".equals(solicitud.getMethod())
                && (ruta.equals("/internal/v1/capacidad/reservas")
                        || ruta.matches("/internal/v1/capacidad/reservas/[^/]+/liberacion")
                        || ruta.matches("/internal/v1/proyecciones/despachos/[^/]+"))) {
            sujeto = "modulo-despacho";
            tipo = "servicio";
        }
        Instant ahora = Instant.now();
        Jwt identidad =
                Jwt.withTokenValue("identidad-demo-interna")
                        .header("alg", "none")
                        .subject(sujeto)
                        .issuedAt(ahora)
                        .expiresAt(ahora.plusSeconds(300))
                        .claim("tipo", tipo)
                        .claim("roles", List.of("GESTOR_DESPACHO"))
                        .claim("demo", true)
                        .build();
        List<SimpleGrantedAuthority> permisos =
                tipo.equals("servicio")
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_GESTOR_DESPACHO"));
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
