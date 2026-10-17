package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.ErrorEvidencia;

@Service
@Profile("!integracion-local")
public class AutorizarLecturaEvidencia {
    private final LectorEvidencias lector;
    private final FirmadorEvidencia firmador;
    public AutorizarLecturaEvidencia(LectorEvidencias lector, FirmadorEvidencia firmador) { this.lector = lector; this.firmador = firmador; }

    public AccesoEvidencia autorizar(String idEvidencia, String idUsuario, boolean gestor) {
        if (!gestor && !lector.tienePerfilActivo(idUsuario)) throw denegado();
        var referencia = lector.consultar(idEvidencia).orElseThrow(() -> new ErrorEvidencia(404,
                "DESP_ERROR_EVIDENCIA_NO_ENCONTRADA", "La evidencia no existe"));
        if (!gestor && !lector.esPropietario(idUsuario, referencia.idDespacho())) throw denegado();
        if (!"ASOCIADA".equals(referencia.estado())) throw new ErrorEvidencia(409,
                "DESP_ERROR_EVIDENCIA_NO_ASOCIADA", "La evidencia todavía no está asociada al despacho");
        int vigencia = lector.vigenciaSegundos();
        if (vigencia < 1 || vigencia > 604800) throw ErrorEvidencia.noDisponible();
        var acceso = firmador.firmar(referencia.rutaObjeto(), vigencia);
        return new AccesoEvidencia(referencia.idEvidencia(), referencia.idDespacho(), acceso.urlLectura(), acceso.venceEn());
    }
    private ErrorEvidencia denegado() { return new ErrorEvidencia(403, "DESP_ERROR_ACCESO_DENEGADO", "No tienes permiso para consultar esta evidencia"); }
    public record AccesoEvidencia(String idEvidencia, String idDespacho, String urlLectura, Instant venceEn) {}
}
