package com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application;

import java.util.UUID;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;

public interface AlmacenCampo {
    /** Registra la clave o devuelve su estado; otra operación o contenido con la misma clave responde 409. */
    Comando iniciar(UUID usuario, UUID clave, String operacion, String huella);
    void completar(UUID usuario, UUID clave, String respuesta);
    /** Un rechazo definitivo libera la clave: repetirla vuelve a validar y obtiene el mismo rechazo. */
    void descartar(UUID usuario, UUID clave);
    EvidenciaPendiente evidencia(UUID idEvidencia, UUID idDespacho, String tipo, String usuario);
    void marcarCargada(UUID idEvidencia);
    void asociar(UUID idEvidencia);
}
