package com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** La audiencia solo es obligatoria en tokens de servicio, conforme al contrato. */
public final class ValidadorTokenDespacho implements OAuth2TokenValidator<Jwt> {

    private final String audiencia;

    public ValidadorTokenDespacho(String audiencia) {
        this.audiencia = audiencia;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object tipo = token.getClaims().get("tipo");
        boolean identidadValida = token.getSubject() != null && !token.getSubject().isBlank();
        boolean tipoValido = "acceso".equals(tipo) || "servicio".equals(tipo);
        boolean audienciaValida = !"servicio".equals(tipo)
                || token.getAudience() != null && token.getAudience().contains(audiencia);
        Object roles = token.getClaims().get("roles");
        boolean perfilValido = "acceso".equals(tipo)
                ? token.getSubject() != null && token.getSubject().matches(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    && roles instanceof List<?> lista && lista.stream().allMatch(String.class::isInstance)
                : token.getClaims().get("scope") instanceof String;
        boolean identificadorValido = token.getClaims().get("jti") instanceof String identificador
                && !identificador.isBlank();
        if (identidadValida && tipoValido && audienciaValida && perfilValido && identificadorValido
                && token.getIssuedAt() != null && token.getExpiresAt() != null) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "El token no cumple el contrato de Despacho", null));
    }
}
