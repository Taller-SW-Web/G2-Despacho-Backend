package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.TarifaZona;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("!integracion-local")
public class GestionTarifas {

    private final RepositorioZonas repositorioZonas;
    private final RepositorioTarifas repositorioTarifas;
    private final RegistroAuditoria auditoria;
    private final ProveedorUsuario usuario;
    private final JsonMapper json;

    public GestionTarifas(RepositorioZonas repositorioZonas, RepositorioTarifas repositorioTarifas,
            RegistroAuditoria auditoria, ProveedorUsuario usuario, JsonMapper json) {
        this.repositorioZonas = repositorioZonas;
        this.repositorioTarifas = repositorioTarifas;
        this.auditoria = auditoria;
        this.usuario = usuario;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Optional<TarifaVista> vigente(UUID zonaId) {
        if (!repositorioZonas.buscarPorId(zonaId).isPresent()) {
            throw ErrorDeNegocio.de(404, CodigosError.ZONA_NO_ENCONTRADA, "La zona no existe");
        }
        return repositorioTarifas.vigente(zonaId).map(GestionTarifas::aVista);
    }

    @Transactional
    public TarifaVista guardar(UUID zonaId, SolicitudTarifa solicitud) {
        var zona = repositorioZonas.buscarPorId(zonaId).orElseThrow(() -> ErrorDeNegocio.de(
                404, CodigosError.ZONA_NO_ENCONTRADA, "La zona no existe"));
        if (zona.getEstado() != EstadoZona.ACTIVO) {
            throw ErrorDeNegocio.de(400, CodigosError.ZONA_INACTIVA,
                    "Solo se puede tarifar una zona en estado ACTIVO");
        }
        validar(solicitud);

        Optional<TarifaZona> anterior = repositorioTarifas.vigente(zonaId);
        String datosAnteriores = anterior.map(tarifa -> serializar(aVista(tarifa))).orElse(null);
        anterior.ifPresent(tarifa -> repositorioTarifas.desactivarVigente(zonaId));

        TarifaZona tarifa = new TarifaZona();
        tarifa.setId(UUID.randomUUID());
        tarifa.setZona(zona);
        tarifa.setTarifaBase(solicitud.tarifaBase());
        tarifa.setPesoBaseKg(solicitud.pesoIncluidoKg());
        tarifa.setRecargoKgAdicional(solicitud.recargoKgAdicional() == null
                ? BigDecimal.ZERO : solicitud.recargoKgAdicional());
        tarifa.setFactorVolumetrico(solicitud.factorVolumetrico());
        tarifa.setMoneda(moneda(solicitud.moneda()));
        tarifa.setPlazoMinDias(solicitud.plazoEstimadoDiasHabiles());
        tarifa.setPlazoMaxDias(solicitud.plazoEstimadoDiasHabiles());
        tarifa.setActiva(true);
        tarifa.setCreadoPor(usuario.idUuid().orElse(null));
        repositorioTarifas.guardar(tarifa);

        TarifaVista vista = aVista(tarifa);
        auditoria.registrar("tarifa", tarifa.getId().toString(),
                anterior.isPresent() ? AccionAuditoria.MODIFICAR : AccionAuditoria.CREAR,
                datosAnteriores, serializar(vista), usuario.identificador());
        return vista;
    }

    private void validar(SolicitudTarifa solicitud) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (solicitud.tarifaBase() == null || solicitud.tarifaBase().signum() < 0) {
            errores.add(new ErrorDeCampo("tarifaBase", "La tarifa base no puede ser negativa"));
        }
        if (solicitud.pesoIncluidoKg() == null || solicitud.pesoIncluidoKg().signum() <= 0) {
            errores.add(new ErrorDeCampo("pesoIncluidoKg", "El peso incluido debe ser mayor que cero"));
        }
        if (solicitud.recargoKgAdicional() != null && solicitud.recargoKgAdicional().signum() < 0) {
            errores.add(new ErrorDeCampo("recargoKgAdicional", "El recargo no puede ser negativo"));
        }
        if (solicitud.factorVolumetrico() != null && solicitud.factorVolumetrico().signum() <= 0) {
            errores.add(new ErrorDeCampo("factorVolumetrico", "El factor volumétrico debe ser mayor que cero"));
        }
        if (solicitud.plazoEstimadoDiasHabiles() == null || solicitud.plazoEstimadoDiasHabiles() < 0) {
            errores.add(new ErrorDeCampo("plazoEstimadoDiasHabiles", "El plazo debe ser un entero no negativo"));
        }
        if (!errores.isEmpty()) {
            throw ErrorDeNegocio.de(400, CodigosError.TARIFA_INVALIDA,
                    "La tarifa contiene campos inválidos", errores);
        }
    }

    private static String moneda(String moneda) {
        return moneda == null || moneda.isBlank() ? "PEN" : moneda.trim();
    }

    public static TarifaVista aVista(TarifaZona tarifa) {
        Integer plazo = tarifa.getPlazoMinDias() != null ? tarifa.getPlazoMinDias() : tarifa.getPlazoMaxDias();
        return new TarifaVista(tarifa.getTarifaBase(), tarifa.getPesoBaseKg(), tarifa.getRecargoKgAdicional(),
                tarifa.getFactorVolumetrico(), tarifa.getMoneda(), plazo, tarifa.getActualizadoEn());
    }

    private String serializar(Object valor) {
        return valor == null ? null : json.writeValueAsString(valor);
    }
}
