package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.CoberturaProgramacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.ZonaDisponible;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioTarifas;

@Component
@Profile("!integracion-local")
@Transactional(readOnly = true)
public class CoberturaProgramacionLocal implements CoberturaProgramacion {
    private final RepositorioZonas zonas;
    private final RepositorioTarifas tarifas;
    private final JdbcClient sql;
    public CoberturaProgramacionLocal(RepositorioZonas zonas, RepositorioTarifas tarifas, JdbcClient sql) {
        this.zonas = zonas; this.tarifas = tarifas; this.sql = sql;
    }
    @Override public ZonaDisponible resolver(String distrito, String codigoPostal) {
        var zona = zonas.resolverCobertura(distrito, codigoPostal).orElseThrow(() ->
                ErrorDeNegocio.de(422, "DESP_ERROR_SIN_COBERTURA", "El destino no pertenece a una zona activa"));
        var tarifa = tarifas.vigente(zona.getId()).orElseThrow(CoberturaProgramacionLocal::sinPlazo);
        Integer dias = tarifa.getPlazoMaxDias();
        if (dias == null || dias < 0) throw sinPlazo();
        return new ZonaDisponible(zona.getId(), zona.getNombre(), distrito, codigoPostal, dias);
    }
    @Override public ZonaDisponible paraSimulacion(String idZona) {
        return sql.sql("""
                SELECT z.id, z.nombre, min(d.nombre) AS distrito, min(zd.codigo_postal) AS codigo_postal FROM zonas z
                JOIN zonas_distritos zd ON zd.zona_id = z.id
                LEFT JOIN distrito d ON d.id = zd.distrito_id
                WHERE z.estado = 'ACTIVO' AND (:id IS NULL OR z.id::text = :id)
                GROUP BY z.id, z.nombre ORDER BY random() LIMIT 1
                """).param("id", idZona, java.sql.Types.VARCHAR)
                .query((fila, indice) -> new ZonaDisponible(fila.getObject("id", UUID.class), fila.getString("nombre"),
                        fila.getString("distrito") == null ? "Distrito de prueba" : fila.getString("distrito"), fila.getString("codigo_postal"), 0))
                .optional().orElseThrow(() -> ErrorDeNegocio.de(422, "DESP_ERROR_SIN_COBERTURA", "No hay una zona activa para generar el despacho de prueba"));
    }
    private static ErrorDeNegocio sinPlazo() { return ErrorDeNegocio.de(503, "DESP_ERROR_PROGRAMACION_NO_DISPONIBLE", "La zona no tiene un plazo operativo configurado"); }
}
