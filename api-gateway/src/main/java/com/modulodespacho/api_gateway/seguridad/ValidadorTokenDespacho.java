package com.modulodespacho.api_gateway.seguridad;

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
        String tipo = token.getClaimAsString("tipo");
        boolean identidadValida = token.getSubject() != null && !token.getSubject().isBlank();
        boolean tipoValido = "acceso".equals(tipo) || "servicio".equals(tipo);
        boolean audienciaValida = !"servicio".equals(tipo)
                || token.getAudience() != null && token.getAudience().contains(audiencia);
        if (identidadValida && tipoValido && audienciaValida && token.getExpiresAt() != null) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "El token no cumple el contrato de Despacho", null));
    }
}
