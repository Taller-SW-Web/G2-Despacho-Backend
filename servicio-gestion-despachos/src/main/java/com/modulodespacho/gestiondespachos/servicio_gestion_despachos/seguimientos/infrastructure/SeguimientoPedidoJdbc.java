package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.LectorSeguimientoPedido;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.SeguimientoPedido;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.seguimientos.application.SeguimientoPedido.Hito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!integracion-local")
public class SeguimientoPedidoJdbc implements LectorSeguimientoPedido {
    private final JdbcClient sql;

    public SeguimientoPedidoJdbc(JdbcClient sql) {
        this.sql = sql;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeguimientoPedido consultar(String idPedido) {
        // Una instantánea para que estado e hitos correspondan a la misma versión del despacho.
        var despacho =
                sql.sql(
                                """
                SELECT id, pedido_id, estado, fecha_programada, distrito, actualizado_en
                FROM despachos WHERE pedido_id = :pedido AND es_simulado = false
                """)
                        .param("pedido", idPedido)
                        .query(
                                (fila, indice) -> {
                                    EstadoDespacho estado =
                                            EstadoDespacho.valueOf(fila.getString("estado"));
                                    return new Base(
                                            fila.getObject("id", UUID.class),
                                            new SeguimientoPedido(
                                                    fila.getString("pedido_id"),
                                                    estado,
                                                    SeguimientoPedido.etiqueta(estado),
                                                    fila.getObject(
                                                            "fecha_programada", LocalDate.class),
                                                    fila.getString("distrito"),
                                                    java.util.List.of(),
                                                    fila.getObject(
                                                                    "actualizado_en",
                                                                    OffsetDateTime.class)
                                                            .toInstant()));
                                })
                        .optional()
                        .orElseThrow(
                                () ->
                                        ErrorDeNegocio.de(
                                                404,
                                                "DESP_ERROR_DESPACHO_NO_ENCONTRADO",
                                                "El pedido no tiene un despacho disponible"));

        // Selección explícita: nunca se leen observaciones, motivos, actores o evidencias.
        var hitos =
                sql.sql(
                                """
                SELECT estado_anterior, estado_nuevo, registrado_en,
                  datos ->> 'nuevaFechaProgramada' AS nueva_fecha_programada
                FROM historial_estados_despacho
                WHERE despacho_id = :despacho AND tipo_operacion = 'TRANSICION'
                ORDER BY registrado_en, id
                """)
                        .param("despacho", despacho.id())
                        .query(
                                (fila, indice) -> {
                                    EstadoDespacho estado =
                                            EstadoDespacho.valueOf(fila.getString("estado_nuevo"));
                                    String etiqueta = SeguimientoPedido.etiqueta(estado);
                                    if (estado == EstadoDespacho.PENDIENTE_ASIGNACION
                                            && "FALLIDO"
                                                    .equals(fila.getString("estado_anterior"))) {
                                        String fecha = fila.getString("nueva_fecha_programada");
                                        etiqueta =
                                                fecha == null
                                                        ? "Entrega reprogramada"
                                                        : "Entrega reprogramada para "
                                                                + LocalDate.parse(fecha);
                                    }
                                    return new Hito(
                                            estado,
                                            etiqueta,
                                            fila.getObject("registrado_en", OffsetDateTime.class)
                                                    .toInstant());
                                })
                        .list();
        SeguimientoPedido base = despacho.seguimiento();
        return new SeguimientoPedido(
                base.idPedido(),
                base.estado(),
                base.estadoEtiqueta(),
                base.fechaProgramada(),
                base.distrito(),
                hitos,
                base.actualizadoEn());
    }

    private record Base(UUID id, SeguimientoPedido seguimiento) {}
}
