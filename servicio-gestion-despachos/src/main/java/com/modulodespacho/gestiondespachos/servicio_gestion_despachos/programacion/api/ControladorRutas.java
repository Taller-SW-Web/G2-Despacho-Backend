package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure.RutasPersistencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosRuta.*;

@RestController
@Profile("!integracion-local")
@RequestMapping("/api/v1/jornadas/{idJornada}")
public class ControladorRutas {
    private final RutasPersistencia rutas;
    private final LectorDatosOperativos operacion;
    private final Clock reloj;
    public ControladorRutas(RutasPersistencia rutas, LectorDatosOperativos operacion, Clock reloj) {
        this.rutas = rutas; this.operacion = operacion; this.reloj = reloj;
    }
    @GetMapping("/ruta")
    public ResponseEntity<Ruta> consultar(@PathVariable String idJornada, HttpServletRequest solicitud) {
        sinParametros(solicitud);
        return respuesta(rutas.consultar(idJornada, estado(idJornada), reloj.instant()));
    }
    @PutMapping("/secuencia")
    public ResponseEntity<Ruta> guardar(@PathVariable String idJornada, @Valid @RequestBody Secuencia cuerpo,
            @RequestHeader("If-Match") String version, @RequestHeader("Idempotency-Key") UUID clave,
            @AuthenticationPrincipal Jwt identidad, HttpServletRequest solicitud) {
        sinParametros(solicitud);
        if (clave.version() != 4 || !version.matches("\"[a-f0-9]{64}\""))
            throw ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Se requieren UUID v4 e If-Match de la ruta consultada");
        var previa = rutas.recuperar(identidad.getSubject(), clave, rutas.solicitud(idJornada, version, cuerpo));
        // Recuperar una respuesta perdida no depende de que la jornada siga activa.
        return respuesta(previa.orElseGet(() -> rutas.guardar(idJornada, estado(idJornada), version, cuerpo,
                identidad.getSubject(), clave, reloj.instant())));
    }
    private String estado(String jornada) {
        var dato = operacion.consultar(List.of(), List.of(jornada)).jornadas().get(jornada);
        if (dato == null || dato.estado() == EstadoJornada.DESCONOCIDA)
            throw ErrorDeNegocio.de(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo confirmar la jornada");
        return dato.estado().name();
    }
    private static ResponseEntity<Ruta> respuesta(Ruta ruta) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(ruta.versionRuta()).body(ruta);
    }
    private static void sinParametros(HttpServletRequest solicitud) {
        if (!solicitud.getParameterMap().isEmpty()) throw ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "La operación no admite parámetros de consulta");
    }
}
