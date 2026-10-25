package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.PoliticaCargaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.domain.EvidenciaNoDisponible;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

/**
 * Comandos de F-03. Sin transacción propia: el HTTP hacia Gestión ocurre entre escrituras locales cortas,
 * y cada paso es repetible con la misma clave (Gestión y la proyección son idempotentes).
 */
@Service
@Profile("!integracion-local")
public class OperacionCampo {
    private final GestorJornadas jornadas;
    private final AlmacenCampo almacen;
    private final PuertoGestion gestion;
    private final VerificadorCargaStorage storage;
    private final PoliticaCargaEvidencia politica;
    private final JsonMapper json;
    public OperacionCampo(GestorJornadas jornadas, AlmacenCampo almacen, PuertoGestion gestion,
            VerificadorCargaStorage storage, PoliticaCargaEvidencia politica, JsonMapper json) {
        this.jornadas = jornadas; this.almacen = almacen; this.gestion = gestion;
        this.storage = storage; this.politica = politica; this.json = json;
    }

    public DetalleDespacho iniciarTraslado(UUID usuario, UUID idDespacho, UUID clave) {
        return comando(usuario, clave, "INICIO_TRASLADO", Map.of("idDespacho", idDespacho), DetalleDespacho.class, nuevo -> {
            DespachoEnRuta d = jornadas.despachoEnRuta(usuario, idDespacho);
            if (nuevo) exigirEstado(d, "ASIGNADO");
            aplicar(d, gestion.transicionar(idDespacho, clave, solicitud(d, "EN_CAMINO", null, null, null, null, usuario)));
            return jornadas.consultarDespachoPropio(usuario, idDespacho);
        });
    }

    public DetalleDespacho registrarEntrega(UUID usuario, UUID idDespacho, UUID clave, UUID idEvidencia, String recibidoPor) {
        String receptor = recibidoPor == null || recibidoPor.isBlank() ? null : recibidoPor.strip();
        return comando(usuario, clave, "ENTREGA", Map.of("idDespacho", idDespacho, "idEvidencia", idEvidencia,
                "recibidoPor", receptor == null ? "" : receptor), DetalleDespacho.class, nuevo -> {
            DespachoEnRuta d = jornadas.despachoEnRuta(usuario, idDespacho);
            if (nuevo) { exigirEstado(d, "EN_CAMINO"); verificarEvidencia(idEvidencia, idDespacho, "ENTREGA", usuario); }
            aplicar(d, gestion.transicionar(idDespacho, clave, solicitud(d, "ENTREGADO", idEvidencia, receptor, null, null, usuario)));
            almacen.asociar(idEvidencia);
            return jornadas.consultarDespachoPropio(usuario, idDespacho);
        });
    }

    public DetalleDespacho registrarFallo(UUID usuario, UUID idDespacho, UUID clave, String motivo, UUID idEvidencia, String comentario) {
        String nota = comentario == null || comentario.isBlank() ? null : comentario.strip();
        return comando(usuario, clave, "FALLO", Map.of("idDespacho", idDespacho, "motivo", motivo, "idEvidencia", idEvidencia,
                "comentario", nota == null ? "" : nota), DetalleDespacho.class, nuevo -> {
            // NO_INTENTADO es exclusivo del cierre (ES-F03-06 RN-03); Gestión valida además el catálogo.
            if ("NO_INTENTADO".equals(motivo)) throw new ErrorJornada(400, "DESP_ERROR_MOTIVO_INVALIDO", "El motivo no se puede registrar en campo.");
            DespachoEnRuta d = jornadas.despachoEnRuta(usuario, idDespacho);
            if (nuevo) { exigirEstado(d, "EN_CAMINO"); verificarEvidencia(idEvidencia, idDespacho, "FALLO", usuario); }
            aplicar(d, gestion.transicionar(idDespacho, clave, solicitud(d, "FALLIDO", idEvidencia, null, motivo, nota, usuario)));
            almacen.asociar(idEvidencia);
            return jornadas.consultarDespachoPropio(usuario, idDespacho);
        });
    }

    /** ES-F03-07: los pendientes pasan a FALLIDO/NO_INTENTADO antes de cerrar; cada uno con su propia clave derivada. */
    public Jornada cerrarJornada(UUID usuario, UUID idJornada, UUID clave) {
        return comando(usuario, clave, "CIERRE_JORNADA", Map.of("idJornada", idJornada), Jornada.class, nuevo -> {
            for (DespachoEnRuta d : jornadas.pendientesPropios(usuario, idJornada)) {
                UUID derivada = UUID.nameUUIDFromBytes((clave + ":" + d.despacho().idDespacho()).getBytes(StandardCharsets.UTF_8));
                aplicar(d, gestion.transicionar(d.despacho().idDespacho(), derivada,
                        solicitud(d, "FALLIDO", null, null, "NO_INTENTADO", null, usuario)));
            }
            return jornadas.cerrarPropia(usuario, idJornada);
        });
    }

    public List<Motivo> motivosDeCampo() {
        return gestion.motivos().stream().filter(m -> m.activo() && m.seleccionableEnCampo()).toList();
    }

    private <T> T comando(UUID usuario, UUID clave, String operacion, Map<String, Object> contenido, Class<T> tipo,
            Function<Boolean, T> ejecutar) {
        Comando comando = almacen.iniciar(usuario, clave, operacion, huella(operacion, contenido));
        if ("COMPLETADO".equals(comando.estado())) return json.readValue(comando.respuesta(), tipo);
        T resultado;
        try {
            // Un reintento de una clave en proceso no repite validaciones locales: Gestión devuelve el resultado original.
            resultado = ejecutar.apply(comando.nuevo());
        } catch (ErrorJornada error) {
            if (error.estado() != 503) almacen.descartar(usuario, clave);
            throw error;
        }
        almacen.completar(usuario, clave, json.writeValueAsString(resultado));
        return resultado;
    }

    private void aplicar(DespachoEnRuta d, RespuestaTransicion respuesta) {
        Proyeccion actual = d.despacho();
        jornadas.proyectar(actual.idDespacho(), actual.conTransicion(respuesta.estado(), respuesta.numeroIntentos(),
                respuesta.version(), respuesta.registradoEn(), respuesta.motivoFallo(), respuesta.hitos()), "GESTION_DESPACHOS");
    }

    private void verificarEvidencia(UUID idEvidencia, UUID idDespacho, String tipo, UUID usuario) {
        EvidenciaPendiente evidencia = almacen.evidencia(idEvidencia, idDespacho, tipo, usuario.toString());
        VerificadorCargaStorage.ObjetoCargado objeto;
        try {
            objeto = storage.verificar(evidencia.rutaObjeto()).orElseThrow(() -> new ErrorJornada(409,
                    "DESP_ERROR_EVIDENCIA_NO_CARGADA", "La fotografía aún no termina de subir. Reintenta."));
        } catch (EvidenciaNoDisponible error) {
            throw new ErrorJornada(503, "DESP_ERROR_EVIDENCIA_NO_DISPONIBLE", "No se pudo comprobar la fotografía. Reintenta.");
        }
        try { politica.validar(objeto.contentType(), objeto.bytes()); }
        catch (RuntimeException error) {
            throw new ErrorJornada(400, "DESP_ERROR_EVIDENCIA_INVALIDA", "La fotografía no cumple el formato o tamaño permitido.");
        }
        if (!objeto.contentType().equalsIgnoreCase(evidencia.tipoMime()))
            throw new ErrorJornada(400, "DESP_ERROR_EVIDENCIA_INVALIDA", "La fotografía no coincide con la carga autorizada.");
        almacen.marcarCargada(idEvidencia);
    }

    private static void exigirEstado(DespachoEnRuta d, String esperado) {
        if (!esperado.equals(d.despacho().estado())) throw new ErrorJornada(409, "DESP_ERROR_ESTADO_INCOMPATIBLE",
                "El despacho fue actualizado y ya no admite esta acción. Actualiza tu ruta.");
    }
    private static SolicitudTransicion solicitud(DespachoEnRuta d, String destino, UUID evidencia, String receptor,
            String motivo, String comentario, UUID usuario) {
        return new SolicitudTransicion(destino, d.idRepartidor().toString(), d.idJornada().toString(),
                evidencia == null ? null : evidencia.toString(), receptor, motivo, comentario, usuario.toString(), "MANUAL");
    }
    private String huella(String operacion, Map<String, Object> contenido) {
        try {
            byte[] datos = json.writeValueAsString(Map.of("operacion", operacion, "contenido", new java.util.TreeMap<>(contenido)))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
