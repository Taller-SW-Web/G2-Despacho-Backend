package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Devuelve a la cola un evento hacia Ventas que agotó sus reintentos. El contenido y el
 * idEvento no cambian, de modo que Ventas sigue deduplicando por el mismo identificador.
 */
@Repository
@Profile("!integracion-local")
public class ReenvioEventosVentas {
    private static final Logger REGISTRO = LoggerFactory.getLogger(ReenvioEventosVentas.class);
    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final JdbcClient sql;

    public ReenvioEventosVentas(JdbcClient sql) {
        this.sql = sql;
    }

    public Resultado reenviar(String idDespacho, String idEvento, String actor) {
        String despacho = normalizar(idDespacho, "idDespacho");
        String evento = normalizar(idEvento, "idEvento");
        // La condición sobre ENVIO_FALLIDO hace atómica la decisión: dos reenvíos simultáneos
        // no reinician dos veces el contador ni pisan un envío en curso.
        int cambios =
                sql.sql(
                                """
                UPDATE eventos_salientes SET estado_envio = 'PENDIENTE', intentos_envio = 0,
                    proximo_intento_en = now(), bloqueada_hasta = NULL, bloqueo_id = NULL, actualizado_en = now()
                WHERE id::text = :evento AND despacho_id::text = :despacho AND destino = 'VENTAS'
                    AND estado_envio = 'ENVIO_FALLIDO'
                """)
                        .param("evento", evento)
                        .param("despacho", despacho)
                        .update();
        if (cambios == 1) {
            REGISTRO.info(
                    "Evento hacia Ventas devuelto a la cola. evento={} despacho={} actor={}",
                    evento,
                    despacho,
                    actor);
            return new Resultado(evento, despacho, "PENDIENTE");
        }
        String estado =
                sql.sql(
                                """
                SELECT estado_envio FROM eventos_salientes
                WHERE id::text = :evento AND despacho_id::text = :despacho AND destino = 'VENTAS'
                """)
                        .param("evento", evento)
                        .param("despacho", despacho)
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        ErrorDeNegocio.de(
                                                404,
                                                "DESP_ERROR_EVENTO_NO_ENCONTRADO",
                                                "No existe ese evento hacia Ventas para el despacho"));
        throw ErrorDeNegocio.de(
                        409,
                        "DESP_ERROR_EVENTO_NO_REENVIABLE",
                        "Solo se puede reenviar un evento en ENVIO_FALLIDO")
                .conEstadoActual(estado);
    }

    private static String normalizar(String valor, String campo) {
        String texto = valor == null ? "" : valor.strip().toLowerCase(Locale.ROOT);
        if (!texto.matches(UUID))
            throw ErrorDeNegocio.de(
                    400,
                    "DESP_ERROR_CAMPOS_INVALIDOS",
                    "El identificador " + campo + " debe ser un UUID");
        return texto;
    }

    public record Resultado(String idEvento, String idDespacho, String estadoEnvio) {}
}
