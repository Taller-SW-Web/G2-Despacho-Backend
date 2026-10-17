package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.DespachoNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.SolicitudEvidenciaInvalida;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.TipoEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.infrastructure.RepositorioEvidenciaJdbc;

@Service
@Profile("!integracion-local")
public class AutorizadorEvidencia {
    private final PoliticaCargaEvidencia politica;
    private final RepositorioEvidenciaJdbc evidencias;
    private final AutorizadorCargaStorage storage;

    public AutorizadorEvidencia(PoliticaCargaEvidencia politica, RepositorioEvidenciaJdbc evidencias,
            AutorizadorCargaStorage storage) {
        this.politica = politica;
        this.evidencias = evidencias;
        this.storage = storage;
    }

    public Autorizacion autorizar(Solicitud solicitud, String usuarioAutenticado) {
        UUID usuario;
        try {
            usuario = UUID.fromString(usuarioAutenticado);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SolicitudEvidenciaInvalida("La identidad del repartidor no es válida.");
        }

        String contentType = solicitud.contentType().trim().toLowerCase(Locale.ROOT);
        politica.validar(contentType, solicitud.tamanioBytes());
        if (!evidencias.perteneceADespachadorEnRuta(solicitud.idDespacho(), usuario)) {
            throw new DespachoNoDisponible();
        }

        UUID idEvidencia = UUID.randomUUID();
        String rutaObjeto = "evidencias/" + solicitud.idDespacho() + "/" + idEvidencia;
        AutorizadorCargaStorage.AutorizacionStorage autorizacion =
                storage.autorizar(solicitud.idDespacho(), idEvidencia);
        if (!evidencias.registrarAutorizada(idEvidencia, solicitud.idDespacho(), rutaObjeto, solicitud.tipo(),
                contentType, Math.toIntExact(solicitud.tamanioBytes()), usuarioAutenticado, usuario,
                autorizacion.venceEn())) {
            throw new DespachoNoDisponible();
        }
        return new Autorizacion(idEvidencia, autorizacion.urlCarga(), autorizacion.venceEn());
    }

    public record Solicitud(UUID idDespacho, TipoEvidencia tipo, String contentType, long tamanioBytes) {
        public Solicitud {
            if (idDespacho == null || tipo == null || contentType == null || contentType.isBlank()) {
                throw new SolicitudEvidenciaInvalida("Completa los datos requeridos para autorizar la evidencia.");
            }
        }
    }

    public record Autorizacion(UUID idEvidencia, String urlCarga, Instant venceEn) {}
}
