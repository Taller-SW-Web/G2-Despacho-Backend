package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.infrastructure.RecojosPersistencia;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.recojos.application.ModelosRecojo.*;

/** El HTTP hacia Operación ocurre siempre fuera de las transacciones locales. */
@Service
@Profile("!integracion-local")
public class GestorRecojos {
    private static final Set<String> DESTINOS = Set.of("EN_CAMINO", "RECOGIDO", "FALLIDO");
    private final RecojosPersistencia almacen;
    private final PuertoCapacidadRecojo capacidad;
    private final Clock reloj;
    public GestorRecojos(RecojosPersistencia almacen, PuertoCapacidadRecojo capacidad, Clock reloj) {
        this.almacen = almacen; this.capacidad = capacidad; this.reloj = reloj;
    }
    private LocalDate hoy() { return LocalDate.now(reloj.withZone(ZoneId.of("America/Lima"))); }

    public Recojo generarPrueba(UUID idDespacho, SolicitudSimulacion solicitud, String actor) {
        return almacen.crearSimulado(idDespacho, solicitud == null ? null : solicitud.lineas(), actor, hoy(), reloj.instant());
    }
    public Pagina listar(List<String> estados, int pagina, int tamano) { return almacen.listar(estados, pagina, tamano); }
    public List<DespachoElegible> elegibles() { return almacen.elegibles(); }
    public DetalleRecojo detalle(UUID id) { return almacen.detalle(id); }

    public Recojo asignar(UUID id, UUID clave, int version, SolicitudAsignacion solicitud, String actor) {
        Intencion intencion = almacen.prepararAsignacion(id, clave, version, solicitud, actor, hoy());
        if (intencion.respuesta() != null) return intencion.respuesta();
        return procesar(intencion);
    }
    private Recojo procesar(Intencion intencion) {
        if (!almacen.reclamar(intencion.id())) throw ErrorDeNegocio.de(409, "DESP_ERROR_ASIGNACION_EN_PROCESO", "La asignación se está procesando; vuelve a consultar");
        if ("COMPENSACION_PENDIENTE".equals(intencion.estado())) {
            compensar(intencion.id(), intencion.idReserva(), intencion.idDespacho());
            throw ErrorDeNegocio.de(409, "DESP_ERROR_ASIGNACION_COMPENSADA", "La asignación anterior no fue confirmada");
        }
        String reserva;
        try {
            reserva = capacidad.reservarRecojo(intencion.clave(), intencion.idRecojo(), intencion.idDespacho(), intencion.idRepartidor(),
                    intencion.idJornada(), intencion.pesoKg(), intencion.volumenM3(), intencion.paquetes());
        } catch (ErrorDeNegocio error) {
            if (error.getEstado() == 409 || error.getEstado() == 422) almacen.rechazar(intencion.id());
            else almacen.aplazar(intencion.id());
            throw error;
        } catch (RuntimeException error) {
            almacen.aplazar(intencion.id());
            throw ErrorDeNegocio.de(503, "DESP_ERROR_OPERACION_NO_DISPONIBLE", "No se pudo confirmar la reserva de capacidad");
        }
        try { return almacen.confirmarAsignacion(intencion, reserva, reloj.instant()); }
        catch (RuntimeException error) {
            if (!almacen.prepararCompensacion(intencion.id(), reserva)) {
                return almacen.resultadoAsignacion(intencion.id()).orElseThrow(() -> ErrorDeNegocio.de(503,
                        "DESP_ERROR_ASIGNACION_NO_DISPONIBLE", "Consulta el resultado de la asignación antes de reintentar"));
            }
            compensar(intencion.id(), reserva, intencion.idDespacho());
            if (error instanceof ErrorDeNegocio negocio) throw negocio;
            throw ErrorDeNegocio.de(503, "DESP_ERROR_ASIGNACION_NO_DISPONIBLE", "No se pudo confirmar la asignación; consulta su resultado antes de reintentar");
        }
    }
    private void compensar(UUID operacion, String reserva, UUID despacho) {
        try { capacidad.compensarRecojo(reserva, despacho.toString()); almacen.finalizarCompensacion(operacion); }
        catch (RuntimeException error) { almacen.aplazar(operacion); }
    }
    public void recuperarPendientes() {
        for (Intencion intencion : almacen.recuperables()) {
            try { procesar(intencion); }
            catch (RuntimeException error) { /* La intención conserva estado y próximo intento durables. */ }
        }
    }

    public Recojo recibir(UUID id, UUID clave, int version, SolicitudRecepcion solicitud, String actor) {
        return almacen.recibir(id, clave, version, solicitud, actor, reloj.instant());
    }
    public Recojo reprogramar(UUID id, UUID clave, int version, SolicitudReprogramacion solicitud, String actor) {
        if (solicitud.fechaProgramada().isBefore(hoy())) throw invalido("fechaProgramada", "La fecha no puede ser anterior a hoy");
        return almacen.reprogramar(id, clave, version, solicitud, actor, reloj.instant());
    }
    public Recojo cerrar(UUID id, UUID clave, int version, SolicitudCierre solicitud, String actor) {
        return almacen.cerrar(id, clave, version, solicitud, actor, reloj.instant());
    }

    public RespuestaTransicion transicionar(UUID id, String clave, SolicitudTransicion s, String actor) {
        try { UUID.fromString(clave == null ? "" : clave); }
        catch (IllegalArgumentException error) { throw invalido("Idempotency-Key", "Debe ser un UUID"); }
        if (s.estadoDestino() == null || !DESTINOS.contains(s.estadoDestino())) throw invalido("estadoDestino", "Transición no admitida");
        if (vacio(s.idRepartidor())) throw invalido("idRepartidor", "Es obligatorio");
        if (vacio(s.idJornada())) throw invalido("idJornada", "Es obligatorio");
        if (vacio(s.ejecutor())) throw invalido("ejecutor", "Es obligatorio");
        if (!"MANUAL".equals(s.origen()) && !"AUTOMATICO".equals(s.origen())) throw invalido("origen", "Debe ser MANUAL o AUTOMATICO");
        boolean fallido = "FALLIDO".equals(s.estadoDestino());
        boolean requiereEvidencia = "RECOGIDO".equals(s.estadoDestino()) || fallido && !"NO_INTENTADO".equals(s.motivo());
        if (requiereEvidencia) {
            try { UUID.fromString(s.idEvidencia() == null ? "" : s.idEvidencia()); }
            catch (IllegalArgumentException error) { throw invalido("idEvidencia", "La fotografía es obligatoria"); }
        } else if (s.idEvidencia() != null) throw invalido("idEvidencia", "No corresponde a esta transición");
        if (fallido && vacio(s.motivo())) throw invalido("motivo", "Es obligatorio");
        if (!fallido && s.motivo() != null) throw invalido("motivo", "No corresponde a esta transición");
        if (s.comentario() != null && ("EN_CAMINO".equals(s.estadoDestino()) || s.comentario().length() > 200))
            throw invalido("comentario", "Máximo 200 caracteres y solo con un resultado");
        return almacen.transicionar(id, clave, s, actor, reloj.instant());
    }
    private static boolean vacio(String valor) { return valor == null || valor.isBlank(); }
    private static ErrorDeNegocio invalido(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Revisa los datos del recojo", List.of(new ErrorDeCampo(campo, motivo)));
    }
}
