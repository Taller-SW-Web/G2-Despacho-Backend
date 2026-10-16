package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.CatalogoMotivosFallo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorIncidenciasLocales;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorCatalogoMotivosFallo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosIncidenciaInconsistentesException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.IncidenciaListadoLocal.Motivo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.MotivoFallo;

@Repository
@Profile("!integracion-local")
public class IncidenciasLocalesJdbc implements LectorIncidenciasLocales, CatalogoMotivosFallo, LectorCatalogoMotivosFallo {

    private static final ZoneId ZONA_LIMA = ZoneId.of("America/Lima");
    private static final String CONSULTA = """
            SELECT d.id AS id_despacho, i.id AS id_incidencia, d.version,
                   d.codigo_rastreo_interno, i.registrado_en AS fecha_incidencia,
                   m.codigo AS motivo_codigo, m.etiqueta AS motivo_etiqueta,
                   d.numero_intentos, i.consume_intento,
                   i.repartidor_id_externo, i.asignacion_diaria_id_externa,
                   (i.recibido_en_centro_en IS NOT NULL) AS recibido_en_centro,
                   (i.evidencia_id_externa IS NOT NULL) AS tiene_evidencia,
                   (d.pedido_anulado_en IS NOT NULL) AS pedido_anulado,
                   (SELECT valor FROM parametros_configuracion WHERE clave = 'MAXIMO_INTENTOS') AS maximo_intentos
            FROM despachos d
            LEFT JOIN intentos_entrega i
                ON i.id = d.ultima_incidencia_id AND i.despacho_id = d.id AND i.resultado = 'FALLIDO'
            LEFT JOIN motivos_fallo m ON m.codigo = i.motivo_codigo
            WHERE d.estado = 'FALLIDO'
            """;

    private final JdbcClient cliente;

    public IncidenciasLocalesJdbc(JdbcClient cliente) {
        this.cliente = cliente;
    }

    @Override
    public boolean existeCodigo(String codigo) {
        return cliente.sql("SELECT EXISTS (SELECT 1 FROM motivos_fallo WHERE codigo = :codigo)")
                .param("codigo", codigo).query(Boolean.class).single();
    }

    @Override
    public List<MotivoFallo> consultarTodos() {
        return cliente.sql("""
                SELECT codigo, etiqueta, seleccionable_en_campo, consume_intento, activo
                FROM motivos_fallo ORDER BY codigo
                """).query((fila, indice) -> new MotivoFallo(fila.getString("codigo"), fila.getString("etiqueta"),
                        fila.getBoolean("seleccionable_en_campo"), fila.getBoolean("consume_intento"),
                        fila.getBoolean("activo"))).list();
    }

    @Override
    public List<IncidenciaListadoLocal> consultarBase(String motivo, LocalDate fechaDesde, LocalDate fechaHasta) {
        if (fechaDesde != null && fechaHasta != null && fechaDesde.isAfter(fechaHasta)) {
            throw new IllegalArgumentException("La fecha inicial no puede superar la fecha final");
        }
        String sql = CONSULTA
                + (motivo == null ? "" : " AND m.codigo = :motivo")
                + (fechaDesde == null ? "" : " AND i.registrado_en >= :desde")
                + (fechaHasta == null ? "" : " AND i.registrado_en < :hasta")
                + " ORDER BY d.id";
        JdbcClient.StatementSpec consulta = cliente.sql(sql);
        if (motivo != null) consulta.param("motivo", motivo);
        if (fechaDesde != null) consulta.param("desde", fechaDesde.atStartOfDay(ZONA_LIMA).toOffsetDateTime());
        if (fechaHasta != null) consulta.param("hasta", fechaHasta.plusDays(1).atStartOfDay(ZONA_LIMA).toOffsetDateTime());

        // Una sentencia proporciona una instantánea local consistente, sin consultar tablas de Operación.
        // Se lee toda la base antes de filtrar recepción o paginar; no se calculan totales por página.
        return List.copyOf(consulta.query(this::leerFila).list());
    }

    private IncidenciaListadoLocal leerFila(ResultSet fila, int indice) throws SQLException {
        if (fila.getString("id_incidencia") == null || fila.getString("motivo_codigo") == null) {
            throw new DatosIncidenciaInconsistentesException("La incidencia vigente de un despacho fallido no es coherente");
        }
        return new IncidenciaListadoLocal(
                fila.getString("id_despacho"), fila.getString("id_incidencia"), fila.getInt("version"),
                fila.getString("codigo_rastreo_interno"),
                fila.getObject("fecha_incidencia", OffsetDateTime.class).toInstant(),
                new Motivo(fila.getString("motivo_codigo"), fila.getString("motivo_etiqueta")),
                fila.getInt("numero_intentos"), maximoIntentos(fila.getString("maximo_intentos")),
                fila.getBoolean("consume_intento"), fila.getString("repartidor_id_externo"),
                fila.getString("asignacion_diaria_id_externa"), fila.getBoolean("recibido_en_centro"),
                fila.getBoolean("tiene_evidencia"), fila.getBoolean("pedido_anulado"));
    }

    private static int maximoIntentos(String valor) {
        try {
            if (valor != null && Integer.parseInt(valor) > 0) return Integer.parseInt(valor);
        } catch (NumberFormatException error) {
            throw new DatosIncidenciaInconsistentesException("La política MAXIMO_INTENTOS no es válida", error);
        }
        throw new DatosIncidenciaInconsistentesException("La política MAXIMO_INTENTOS no es válida");
    }
}
