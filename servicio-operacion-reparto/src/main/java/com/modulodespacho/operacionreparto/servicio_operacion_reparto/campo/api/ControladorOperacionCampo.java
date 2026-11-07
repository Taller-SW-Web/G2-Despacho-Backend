package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.OperacionCampo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.Motivo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.DetalleDespacho;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.DetalleRecojo;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

/** Operación §3: el filtro exige el perfil de campo y cada comando resuelve la propiedad por sub. */
@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/repartidor")
public class ControladorOperacionCampo {
    private final OperacionCampo campo;
    private final GestorJornadas jornadas;
    public ControladorOperacionCampo(OperacionCampo campo, GestorJornadas jornadas) { this.campo = campo; this.jornadas = jornadas; }

    @PostMapping("/despachos/{idDespacho}/inicio-traslado")
    public ResponseEntity<DetalleDespacho> iniciarTraslado(@PathVariable UUID idDespacho, @RequestHeader("Idempotency-Key") UUID clave,
            @AuthenticationPrincipal Jwt token, @RequestBody(required = false) String cuerpo) {
        if (cuerpo != null && !cuerpo.isBlank()) throw new ErrorJornada(400, "DESP_ERROR_CAMPOS_INVALIDOS", "El inicio de traslado no recibe cuerpo.");
        return sinCache(campo.iniciarTraslado(usuario(token), idDespacho, claveValida(clave)));
    }
    @PostMapping("/despachos/{idDespacho}/entrega")
    public ResponseEntity<DetalleDespacho> entregar(@PathVariable UUID idDespacho, @RequestHeader("Idempotency-Key") UUID clave,
            @Valid @RequestBody Entrega solicitud, @AuthenticationPrincipal Jwt token) {
        return sinCache(campo.registrarEntrega(usuario(token), idDespacho, claveValida(clave), solicitud.idEvidencia(), solicitud.recibidoPor()));
    }
    @PostMapping("/despachos/{idDespacho}/fallo")
    public ResponseEntity<DetalleDespacho> fallar(@PathVariable UUID idDespacho, @RequestHeader("Idempotency-Key") UUID clave,
            @Valid @RequestBody Fallo solicitud, @AuthenticationPrincipal Jwt token) {
        return sinCache(campo.registrarFallo(usuario(token), idDespacho, claveValida(clave), solicitud.motivo(),
                solicitud.idEvidencia(), solicitud.comentario()));
    }
    @GetMapping("/motivos-fallo")
    public ResponseEntity<Map<String, List<Motivo>>> motivos(@RequestParam(defaultValue = "ENTREGA") String ambito) {
        if (!List.of("ENTREGA", "RECOJO").contains(ambito)) throw new ErrorJornada(400, "DESP_ERROR_CAMPOS_INVALIDOS", "El ámbito debe ser ENTREGA o RECOJO.");
        return sinCache(Map.of("motivos", campo.motivosDeCampo(ambito)));
    }
    @GetMapping("/recojos/{idRecojo}")
    public ResponseEntity<DetalleRecojo> recojo(@PathVariable UUID idRecojo, @AuthenticationPrincipal Jwt token) {
        return sinCache(jornadas.consultarRecojoPropio(usuario(token), idRecojo));
    }
    @PostMapping("/recojos/{idRecojo}/inicio-traslado")
    public ResponseEntity<DetalleRecojo> iniciarTrasladoRecojo(@PathVariable UUID idRecojo, @RequestHeader("Idempotency-Key") UUID clave,
            @AuthenticationPrincipal Jwt token, @RequestBody(required = false) String cuerpo) {
        if (cuerpo != null && !cuerpo.isBlank()) throw new ErrorJornada(400, "DESP_ERROR_CAMPOS_INVALIDOS", "El inicio de traslado no recibe cuerpo.");
        return sinCache(campo.iniciarTrasladoRecojo(usuario(token), idRecojo, claveValida(clave)));
    }
    @PostMapping("/recojos/{idRecojo}/recogido")
    public ResponseEntity<DetalleRecojo> recogido(@PathVariable UUID idRecojo, @RequestHeader("Idempotency-Key") UUID clave,
            @Valid @RequestBody Recogido solicitud, @AuthenticationPrincipal Jwt token) {
        return sinCache(campo.registrarRecogido(usuario(token), idRecojo, claveValida(clave), solicitud.idEvidencia(), solicitud.comentario()));
    }
    @PostMapping("/recojos/{idRecojo}/fallo")
    public ResponseEntity<DetalleRecojo> falloRecojo(@PathVariable UUID idRecojo, @RequestHeader("Idempotency-Key") UUID clave,
            @Valid @RequestBody Fallo solicitud, @AuthenticationPrincipal Jwt token) {
        return sinCache(campo.registrarFalloRecojo(usuario(token), idRecojo, claveValida(clave), solicitud.motivo(), solicitud.idEvidencia(), solicitud.comentario()));
    }

    public record Recogido(@NotNull UUID idEvidencia, @Size(max = 200) String comentario) {}
    public record Entrega(@NotNull UUID idEvidencia, @Size(max = 120) String recibidoPor) {}
    public record Fallo(@NotBlank @Size(max = 40) String motivo, @NotNull UUID idEvidencia, @Size(max = 200) String comentario) {}

    static UUID claveValida(UUID clave) {
        if (clave.version() != 4) throw new ErrorJornada(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Idempotency-Key debe ser UUID v4.");
        return clave;
    }
    static UUID usuario(Jwt token) {
        try { return UUID.fromString(token.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ErrorJornada(403, "DESP_ERROR_IDENTIDAD_INVALIDA", "La identidad no permite registrar la operación.");
        }
    }
    private static <T> ResponseEntity<T> sinCache(T cuerpo) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cuerpo); }
}
