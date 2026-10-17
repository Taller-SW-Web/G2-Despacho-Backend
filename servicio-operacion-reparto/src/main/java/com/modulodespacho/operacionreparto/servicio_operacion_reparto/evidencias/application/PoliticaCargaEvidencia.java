package com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.SolicitudEvidenciaInvalida;

@Component
public class PoliticaCargaEvidencia {
    private final Set<String> tiposPermitidos;
    private final long maximoBytes;
    private final String urlSupabase;
    private final String claveServicio;
    private final String bucket;

    public PoliticaCargaEvidencia(
            @Value("${despacho.evidencias.tipos-permitidos:}") String tiposPermitidos,
            @Value("${despacho.evidencias.maximo-bytes:0}") long maximoBytes,
            @Value("${despacho.evidencias.supabase-url:}") String urlSupabase,
            @Value("${despacho.evidencias.supabase-service-key:}") String claveServicio,
            @Value("${despacho.evidencias.bucket:}") String bucket) {
        this.tiposPermitidos = Arrays.stream(tiposPermitidos.split(","))
                .map(String::trim)
                .filter(tipo -> !tipo.isEmpty())
                .map(tipo -> tipo.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maximoBytes = maximoBytes;
        this.urlSupabase = urlSupabase;
        this.claveServicio = claveServicio;
        this.bucket = bucket;
    }

    public void validar(String contentType, long tamanioBytes) {
        verificarConfiguracion();
        if (contentType == null || !tiposPermitidos.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new SolicitudEvidenciaInvalida("El formato de la fotografía no está permitido.");
        }
        if (tamanioBytes <= 0 || tamanioBytes > maximoBytes || tamanioBytes > Integer.MAX_VALUE) {
            throw new SolicitudEvidenciaInvalida("El tamaño de la fotografía no cumple la política vigente.");
        }
    }

    public String urlSupabase() {
        verificarConfiguracion();
        return urlSupabase;
    }

    public String claveServicio() {
        verificarConfiguracion();
        return claveServicio;
    }

    public String bucket() {
        verificarConfiguracion();
        return bucket;
    }

    private void verificarConfiguracion() {
        if (tiposPermitidos.isEmpty() || maximoBytes <= 0 || maximoBytes > Integer.MAX_VALUE
                || urlSupabase == null || urlSupabase.isBlank()
                || claveServicio == null || claveServicio.isBlank()
                || bucket == null || !bucket.matches("[a-z0-9][a-z0-9._-]{0,99}")
                || tiposPermitidos.stream().anyMatch(tipo -> tipo.length() > 30
                        || !tipo.matches("image/[a-z0-9][a-z0-9.+-]*"))) {
            throw new EvidenciaNoDisponible(
                    "La política y el almacenamiento de evidencia deben configurarse antes de autorizar cargas.");
        }
    }
}
