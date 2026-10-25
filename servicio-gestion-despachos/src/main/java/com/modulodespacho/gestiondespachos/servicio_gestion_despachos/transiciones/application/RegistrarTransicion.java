package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.transiciones.application.ModelosTransicion.*;

/** Valida la forma de la solicitud; la máquina de estados se aplica bajo bloqueo en el almacén. */
@Service
@Profile("!integracion-local")
public class RegistrarTransicion {
    static final Set<String> DESTINOS = Set.of("EN_CAMINO", "ENTREGADO", "FALLIDO");
    private final AlmacenTransiciones almacen;
    private final Clock reloj;
    public RegistrarTransicion(AlmacenTransiciones almacen, Clock reloj) { this.almacen = almacen; this.reloj = reloj; }

    public RespuestaTransicion ejecutar(UUID idDespacho, String clave, SolicitudTransicion solicitud, String actor) {
        validar(clave, solicitud);
        return almacen.registrar(idDespacho, clave, solicitud, actor, reloj.instant());
    }

    private static void validar(String clave, SolicitudTransicion s) {
        try { UUID.fromString(clave == null ? "" : clave); }
        catch (IllegalArgumentException error) { throw invalido("Idempotency-Key", "Debe ser un UUID"); }
        if (s.estadoDestino() == null || !DESTINOS.contains(s.estadoDestino())) throw invalido("estadoDestino", "Transición no admitida");
        if (vacio(s.idRepartidor())) throw invalido("idRepartidor", "Es obligatorio");
        if (vacio(s.idJornada())) throw invalido("idJornada", "Es obligatorio");
        if (vacio(s.ejecutor())) throw invalido("ejecutor", "Es obligatorio");
        if (!"MANUAL".equals(s.origen()) && !"AUTOMATICO".equals(s.origen())) throw invalido("origen", "Debe ser MANUAL o AUTOMATICO");
        boolean noIntentado = "FALLIDO".equals(s.estadoDestino()) && "NO_INTENTADO".equals(s.motivo());
        boolean requiereEvidencia = "ENTREGADO".equals(s.estadoDestino()) || "FALLIDO".equals(s.estadoDestino()) && !noIntentado;
        if (requiereEvidencia) {
            try { UUID.fromString(s.idEvidencia() == null ? "" : s.idEvidencia()); }
            catch (IllegalArgumentException error) { throw invalido("idEvidencia", "La fotografía es obligatoria"); }
        } else if (s.idEvidencia() != null) throw invalido("idEvidencia", "No corresponde a esta transición");
        if ("FALLIDO".equals(s.estadoDestino()) && vacio(s.motivo())) throw invalido("motivo", "Es obligatorio");
        if (!"FALLIDO".equals(s.estadoDestino()) && s.motivo() != null) throw invalido("motivo", "No corresponde a esta transición");
        if (s.comentario() != null && (!"FALLIDO".equals(s.estadoDestino()) || s.comentario().length() > 200))
            throw invalido("comentario", "Máximo 200 caracteres y solo para un fallo");
        if (s.recibidoPor() != null && (!"ENTREGADO".equals(s.estadoDestino()) || s.recibidoPor().isBlank() || s.recibidoPor().length() > 120))
            throw invalido("recibidoPor", "Máximo 120 caracteres y solo para una entrega");
    }
    private static boolean vacio(String valor) { return valor == null || valor.isBlank(); }
    private static ErrorDeNegocio invalido(String campo, String motivo) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Revisa los datos de la transición",
                java.util.List.of(new ErrorDeCampo(campo, motivo)));
    }
}
