package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.GestorRecojos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.ModelosRecojo.*;

/** Gestión §3.3 y §6.3; las rutas /api exigen GESTOR_DESPACHO y la interna, el cliente técnico del módulo. */
@RestController
@Profile("!integracion-local")
public class ControladorRecojos {
    private static final Set<String> ESTADOS = Set.of("PENDIENTE_ASIGNACION", "ASIGNADO", "EN_CAMINO", "RECOGIDO", "FALLIDO",
            "RECIBIDO_EN_CENTRO", "CERRADO_SIN_RECOJO", "CANCELADO");
    private final GestorRecojos recojos;
    public ControladorRecojos(GestorRecojos recojos) { this.recojos = recojos; }

    @GetMapping("/api/v1/recojos")
    public ResponseEntity<Pagina> listar(@RequestParam(defaultValue = "PENDIENTE_ASIGNACION") List<String> estado,
            @RequestParam(defaultValue = "1") int pagina, @RequestParam(defaultValue = "50") int tamanoPagina) {
        if (estado.isEmpty() || !ESTADOS.containsAll(estado) || pagina < 1 || tamanoPagina < 1 || tamanoPagina > 100)
            throw invalido("parametros", "Revisa el estado y la paginación");
        return sinCache(recojos.listar(estado, pagina, tamanoPagina));
    }
    @GetMapping("/api/v1/recojos/elegibles")
    public ResponseEntity<Map<String, List<DespachoElegible>>> elegibles() { return sinCache(Map.of("despachos", recojos.elegibles())); }
    @GetMapping("/api/v1/recojos/{idRecojo}")
    public ResponseEntity<DetalleRecojo> detalle(@PathVariable String idRecojo) { return sinCache(recojos.detalle(id(idRecojo))); }

    @PostMapping("/api/v1/despachos/{idDespacho}/recojos/simulacion")
    public ResponseEntity<Recojo> simular(@PathVariable String idDespacho, @Valid @RequestBody(required = false) SolicitudSimulacion solicitud,
            @AuthenticationPrincipal Jwt identidad) {
        UUID despacho;
        try { despacho = UUID.fromString(idDespacho); }
        catch (IllegalArgumentException error) { throw ErrorDeNegocio.de(404, "DESP_ERROR_DESPACHO_NO_ENCONTRADO", "El despacho no existe"); }
        Recojo creado = recojos.generarPrueba(despacho, solicitud, identidad.getSubject());
        return ResponseEntity.created(URI.create("/api/v1/recojos/" + creado.idRecojo())).cacheControl(CacheControl.noStore()).body(creado);
    }
    @PostMapping("/api/v1/recojos/{idRecojo}/asignacion")
    public ResponseEntity<Recojo> asignar(@PathVariable String idRecojo, @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestHeader(value = "If-Match", required = false) String version, @Valid @RequestBody SolicitudAsignacion solicitud, @AuthenticationPrincipal Jwt identidad) {
        return sinCache(recojos.asignar(id(idRecojo), clave(clave), version(version), solicitud, identidad.getSubject()));
    }
    @PostMapping("/api/v1/recojos/{idRecojo}/recepcion")
    public ResponseEntity<Recojo> recibir(@PathVariable String idRecojo, @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestHeader(value = "If-Match", required = false) String version, @Valid @RequestBody(required = false) SolicitudRecepcion solicitud, @AuthenticationPrincipal Jwt identidad) {
        return sinCache(recojos.recibir(id(idRecojo), clave(clave), version(version), solicitud, identidad.getSubject()));
    }
    @PostMapping("/api/v1/recojos/{idRecojo}/reprogramacion")
    public ResponseEntity<Recojo> reprogramar(@PathVariable String idRecojo, @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestHeader(value = "If-Match", required = false) String version, @Valid @RequestBody SolicitudReprogramacion solicitud, @AuthenticationPrincipal Jwt identidad) {
        return sinCache(recojos.reprogramar(id(idRecojo), clave(clave), version(version), solicitud, identidad.getSubject()));
    }
    @PostMapping("/api/v1/recojos/{idRecojo}/cierre")
    public ResponseEntity<Recojo> cerrar(@PathVariable String idRecojo, @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestHeader(value = "If-Match", required = false) String version, @Valid @RequestBody SolicitudCierre solicitud, @AuthenticationPrincipal Jwt identidad) {
        return sinCache(recojos.cerrar(id(idRecojo), clave(clave), version(version), solicitud, identidad.getSubject()));
    }
    @PostMapping(value = "/internal/v1/recojos/{idRecojo}/transiciones", consumes = "application/json")
    public ResponseEntity<RespuestaTransicion> transicionar(@PathVariable String idRecojo, @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestBody SolicitudTransicion solicitud, @AuthenticationPrincipal Jwt identidad) {
        return sinCache(recojos.transicionar(id(idRecojo), clave, solicitud, identidad.getSubject()));
    }

    private static UUID id(String valor) {
        try { return UUID.fromString(valor); }
        catch (IllegalArgumentException error) { throw ErrorDeNegocio.de(404, "DESP_ERROR_RECOJO_NO_ENCONTRADO", "El recojo no existe"); }
    }
    private static UUID clave(String valor) {
        try {
            UUID clave = UUID.fromString(valor == null ? "" : valor);
            if (clave.version() == 4) return clave;
        } catch (IllegalArgumentException error) { /* Se responde igual que una versión distinta de v4. */ }
        throw invalido("Idempotency-Key", "Debe ser un UUID v4");
    }
    private static int version(String valor) {
        try { return Integer.parseInt(valor == null ? "" : valor.replace("\"", "").strip()); }
        catch (NumberFormatException error) { throw invalido("If-Match", "Debe indicar la versión del recojo"); }
    }
    private static ErrorDeNegocio invalido(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Revisa los datos del recojo", List.of(new ErrorDeCampo(campo, motivo)));
    }
    private static <T> ResponseEntity<T> sinCache(T cuerpo) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cuerpo); }
}
