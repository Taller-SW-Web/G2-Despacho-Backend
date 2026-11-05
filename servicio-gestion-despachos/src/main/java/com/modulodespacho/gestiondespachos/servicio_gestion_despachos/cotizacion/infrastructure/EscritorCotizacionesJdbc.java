package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.InstantaneaCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.RegistroCotizaciones;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** La frontera incluye el commit: incluso un fallo diferido impide devolver una cotización exitosa. */
@Component
@Profile("!integracion-local")
public class EscritorCotizacionesJdbc implements RegistroCotizaciones {
    private static final Logger LOG = LoggerFactory.getLogger(EscritorCotizacionesJdbc.class);
    private final JdbcClient sql;
    private final JsonMapper json;
    private final TransactionTemplate escritura;

    public EscritorCotizacionesJdbc(JdbcClient sql, JsonMapper json, PlatformTransactionManager transacciones) {
        this.sql = sql;
        this.json = json;
        this.escritura = new TransactionTemplate(transacciones);
        this.escritura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void guardar(InstantaneaCotizacion instantanea) {
        try {
            String detalle = json.writeValueAsString(instantanea);
            escritura.executeWithoutResult(estado -> insertar(instantanea, detalle));
        } catch (RuntimeException error) {
            LOG.error("No se pudo confirmar la instantánea de cotización", error);
            throw ErrorDeNegocio.de(500, CodigosError.PERSISTENCIA_COTIZACION,
                    "No fue posible confirmar la cotización");
        }
    }

    private void insertar(InstantaneaCotizacion instantanea, String detalle) {
        var resultado = instantanea.resultado();
        var tarifa = instantanea.tarifa().condiciones();
        sql.sql("""
                INSERT INTO cotizaciones (zona_id, tarifa_zona_id, distrito_id, direccion, distrito,
                    codigo_postal, peso_kg, volumen_m3, cantidad_paquetes, tarifa_base, peso_base_kg,
                    recargo_kg_adicional, factor_volumetrico, peso_volumetrico_kg, peso_cobrable_kg,
                    recargo_total, total, moneda, plazo_min_dias, plazo_max_dias, es_simulado,
                    detalle, creado_en, actualizado_en)
                VALUES (:zona, :tarifa, (SELECT id FROM distrito WHERE lower(nombre) = lower(:distrito)
                    ORDER BY id LIMIT 1), :direccion, :distrito, :postal, :peso, :volumen, 1,
                    :base, :incluido, :recargo, :factor, :volumetrico, :cobrable, :recargoTotal,
                    :total, :moneda, :plazo, :plazo, :simulado, CAST(:detalle AS jsonb), :ahora, :ahora)
                """).param("zona", instantanea.idZona()).param("tarifa", instantanea.tarifa().id())
                .param("distrito", resultado.distrito()).param("direccion", resultado.direccion())
                .param("postal", resultado.codigoPostal()).param("peso", resultado.pesoTotalKg())
                .param("volumen", resultado.volumenTotalM3()).param("base", tarifa.tarifaBase())
                .param("incluido", tarifa.pesoIncluidoKg()).param("recargo", tarifa.recargoKgAdicional())
                .param("factor", tarifa.factorVolumetrico())
                .param("volumetrico", instantanea.totalesExactos().pesoCotizableKg()
                        .subtract(instantanea.totalesExactos().pesoTotalKg()))
                .param("cobrable", instantanea.totalesExactos().pesoCotizableKg())
                .param("recargoTotal", resultado.costoEnvio().subtract(tarifa.tarifaBase()))
                .param("total", resultado.costoEnvio()).param("moneda", resultado.moneda())
                .param("plazo", resultado.plazoEstimadoDiasHabiles()).param("simulado", instantanea.simulado())
                .param("detalle", detalle).param("ahora", java.sql.Timestamp.from(resultado.cotizadoEn())).update();
    }
}
