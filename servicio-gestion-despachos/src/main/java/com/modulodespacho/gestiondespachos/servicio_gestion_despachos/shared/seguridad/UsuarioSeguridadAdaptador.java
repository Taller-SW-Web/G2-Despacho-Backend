package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ProveedorUsuario;

@Component
public class UsuarioSeguridadAdaptador implements ProveedorUsuario {

    @Override
    public String identificador() {
        return subject().orElse("sistema");
    }

    @Override
    public Optional<UUID> idUuid() {
        return subject().flatMap(valor -> {
            try {
                return Optional.of(UUID.fromString(valor));
            } catch (IllegalArgumentException error) {
                return Optional.empty();
            }
        });
    }

    private Optional<String> subject() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion instanceof JwtAuthenticationToken jwt && autenticacion.isAuthenticated()) {
            String subject = jwt.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return Optional.of(subject);
            }
        }
        return Optional.empty();
    }
}
