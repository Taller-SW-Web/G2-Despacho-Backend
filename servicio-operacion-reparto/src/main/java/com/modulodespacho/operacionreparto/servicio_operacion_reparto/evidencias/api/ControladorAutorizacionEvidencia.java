package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.api;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.TipoEvidencia;

@RestController
@RequestMapping("/api/v1/repartidor/evidencias")
@Profile("!integracion-local")
public class ControladorAutorizacionEvidencia {
    private final AutorizadorEvidencia autorizador;

    public ControladorAutorizacionEvidencia(AutorizadorEvidencia autorizador) {
        this.autorizador = autorizador;
    }

    @PostMapping("/autorizaciones")
    public ResponseEntity<RespuestaAutorizacion> autorizar(@RequestBody @Valid SolicitudAutorizacion solicitud,
            @AuthenticationPrincipal Jwt token) {
        AutorizadorEvidencia.Autorizacion resultado = autorizador.autorizar(
                new AutorizadorEvidencia.Solicitud(solicitud.idDespacho(), solicitud.tipo(),
                        solicitud.contentType(), solicitud.tamanioBytes()),
                token.getSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaAutorizacion(resultado.idEvidencia(), resultado.urlCarga(), resultado.venceEn()));
    }

    public record SolicitudAutorizacion(
            @NotNull UUID idDespacho,
            @NotNull TipoEvidencia tipo,
            @NotBlank String contentType,
            @Min(1) long tamanioBytes) {}

    public record RespuestaAutorizacion(UUID idEvidencia, String urlCarga, Instant venceEn) {}
}
