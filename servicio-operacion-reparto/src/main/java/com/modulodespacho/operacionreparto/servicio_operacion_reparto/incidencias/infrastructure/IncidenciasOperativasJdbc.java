package com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.application.LectorIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas.Jornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas.Repartidor;

@Repository
@Profile("!integracion-local")
public class IncidenciasOperativasJdbc implements LectorIncidenciasOperativas {

    private final JdbcClient cliente;

    public IncidenciasOperativasJdbc(JdbcClient cliente) {
        this.cliente = cliente;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DatosIncidenciasOperativas consultar(List<String> idsRepartidor, List<String> idsJornada) {
        Map<UUID, String> nombres = new LinkedHashMap<>();
        List<UUID> repartidores = identificadoresLocales(idsRepartidor);
        if (!repartidores.isEmpty()) {
            cliente.sql("SELECT id, trim(concat_ws(' ', nombres, apellidos)) AS nombre FROM repartidores WHERE id IN (:ids)")
                    .param("ids", repartidores).query((fila, indice) -> {
                        nombres.put(fila.getObject("id", UUID.class), fila.getString("nombre"));
                        return fila.getObject("id", UUID.class);
                    }).list();
        }
        Map<UUID, Jornada> estados = new LinkedHashMap<>();
        List<UUID> jornadas = identificadoresLocales(idsJornada);
        if (!jornadas.isEmpty()) {
            cliente.sql("SELECT id, estado, cerrada_en FROM asignaciones_diarias WHERE id IN (:ids)")
                    .param("ids", jornadas).query((fila, indice) -> {
                        UUID id = fila.getObject("id", UUID.class);
                        OffsetDateTime cierre = fila.getObject("cerrada_en", OffsetDateTime.class);
                        estados.put(id, new Jornada(id.toString(), fila.getString("estado"), cierre == null ? null : cierre.toInstant()));
                        return id;
                    }).list();
        }
        return new DatosIncidenciasOperativas(
                idsRepartidor.stream().map(id -> new Repartidor(id, nombres.get(comoUuid(id)))).toList(),
                idsJornada.stream().map(id -> {
                    Jornada jornada = estados.get(comoUuid(id));
                    return jornada == null ? new Jornada(id, "DESCONOCIDA", null)
                            : new Jornada(id, jornada.estado(), jornada.cerradaEn());
                }).toList(), Instant.now());
    }

    private static List<UUID> identificadoresLocales(List<String> ids) {
        return ids.stream().map(IncidenciasOperativasJdbc::comoUuid).filter(Objects::nonNull).distinct().toList();
    }

    private static UUID comoUuid(String id) {
        try {
            UUID valor = UUID.fromString(id);
            return valor.toString().equalsIgnoreCase(id) ? valor : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
