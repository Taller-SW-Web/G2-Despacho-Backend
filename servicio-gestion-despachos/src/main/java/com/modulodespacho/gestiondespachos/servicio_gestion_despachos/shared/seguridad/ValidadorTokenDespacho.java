package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Valida identidad; el ámbito público exige además audiencia en los tokens de servicio. */
public final class ValidadorTokenDespacho implements OAuth2TokenValidator<Jwt> {

    private final String audiencia;
    private final boolean validarAudiencia;

    public ValidadorTokenDespacho(String audiencia) {
        this(audiencia, true);
    }

    public ValidadorTokenDespacho(String audiencia, boolean validarAudiencia) {
        this.audiencia = audiencia;
        this.validarAudiencia = validarAudiencia;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String tipo = token.getClaimAsString("tipo");
        boolean identidadValida = token.getSubject() != null && !token.getSubject().isBlank();
        boolean tipoValido = "acceso".equals(tipo) || "servicio".equals(tipo);
        boolean audienciaValida = !validarAudiencia || !"servicio".equals(tipo)
                || token.getAudience() != null && token.getAudience().contains(audiencia);
        if (identidadValida && tipoValido && audienciaValida && token.getExpiresAt() != null) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "El token no cumple el contrato de Despacho", null));
    }
}
