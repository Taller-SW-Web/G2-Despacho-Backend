package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** La reclamación se confirma antes del HTTP; nunca retiene una transacción durante la espera. */
public class ProcesadorLiberaciones {
    private static final Logger LOG = LoggerFactory.getLogger(ProcesadorLiberaciones.class);
    private final LiberacionesPendientes pendientes;
    private final LiberadorCapacidad operacion;
    private final Clock reloj;
    private final ExecutorService llamadas;
    private final List<Duration> esperas;
    public ProcesadorLiberaciones(LiberacionesPendientes pendientes, LiberadorCapacidad operacion, Clock reloj,
            ExecutorService llamadas, List<Duration> esperas) {
        if (esperas == null || esperas.isEmpty() || esperas.stream().anyMatch(d -> d.isNegative() || d.isZero()))
            throw new IllegalArgumentException("El calendario de liberación debe contener esperas positivas");
        this.pendientes = pendientes; this.operacion = operacion; this.reloj = reloj; this.llamadas = llamadas;
        this.esperas = List.copyOf(esperas);
    }
    @Scheduled(fixedDelayString = "${despacho.recepcion.intervalo-procesador-ms:1000}")
    public void ejecutar() {
        try { procesarUna(); }
        catch (RuntimeException error) {
            // El bloqueo vence: si falló la escritura o el proceso, otro ciclo recuperará el envío.
            LOG.warn("No se pudo completar el ciclo de liberación de capacidad; se recuperará la solicitud pendiente");
        }
    }
    public boolean procesarUna() {
        var reclamada = pendientes.reclamar(reloj.instant());
        if (reclamada.isEmpty()) return false;
        var envio = reclamada.get();
        if (!envio.contextoValido()) {
            var ahora = reloj.instant();
            pendientes.reintentar(envio, ahora, ahora.plus(Duration.ofHours(1)), true, "DATOS_RECEPCION_INCONSISTENTES");
            return true;
        }
        var llamada = llamadas.submit(() -> operacion.liberar(envio));
        LiberadorCapacidad.Resultado resultado;
        try { resultado = llamada.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            llamada.cancel(true); Thread.currentThread().interrupt(); return true;
        } catch (Exception error) {
            llamada.cancel(true); resultado = LiberadorCapacidad.Resultado.fallo(false, "OPERACION_NO_DISPONIBLE");
        }
        var ahora = reloj.instant();
        if (resultado != null && resultado.confirmada() && resultado.liberadaEn() != null) {
            pendientes.confirmar(envio, ahora, resultado.liberadaEn());
        } else {
            boolean revision = envio.requiereRevision() || resultado == null || resultado.requiereRevision();
            var demora = revision ? Duration.ofHours(1) : esperas.get(Math.min(envio.intento() - 1, esperas.size() - 1));
            pendientes.reintentar(envio, ahora, ahora.plus(demora), revision,
                    resultado == null ? "RESPUESTA_INCOHERENTE" : resultado.codigo());
        }
        return true;
    }
}
