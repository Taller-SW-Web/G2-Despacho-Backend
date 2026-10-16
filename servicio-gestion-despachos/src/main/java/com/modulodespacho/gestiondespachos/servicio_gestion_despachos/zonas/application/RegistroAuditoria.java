package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;

public interface RegistroAuditoria {

    void registrar(String entidad, String entidadId, AccionAuditoria accion,
            String datosAnterioresJson, String datosNuevosJson, String usuarioId);
}
