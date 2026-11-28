package com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.application;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api.SolicitudesJornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.mensajeria.domain.MensajeInterno;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Registra el eventId antes de ejecutar el efecto; una reentrega no repite la operación. */
@Service
@Profile("!integracion-local")
public class ProcesadorMensajesInternos {
    private static final int VERSION_SOPORTADA = 1;
    private static final String ACTOR = "modulo-despacho";

    private final JdbcClient sql;
    private final GestorJornadas gestor;
    private final JsonMapper json;

    public ProcesadorMensajesInternos(JdbcClient sql, GestorJornadas gestor, JsonMapper json) {
        this.sql = sql;
        this.gestor = gestor;
        this.json = json;
    }

    public void procesar(MensajeInterno mensaje) {
        if (mensaje.version() != VERSION_SOPORTADA)
            throw new IllegalArgumentException("Versión del mensaje no soportada");
        int registrados =
                sql.sql(
                                """
                        INSERT INTO eventos_procesados(event_id, procesado_en)
                        VALUES (:id, now()) ON CONFLICT (event_id) DO NOTHING
                        """)
                        .param("id", mensaje.eventId())
                        .update();
        if (registrados == 0) return;
        try {
            despachar(mensaje);
        } catch (RuntimeException error) {
            sql.sql("DELETE FROM eventos_procesados WHERE event_id = :id")
                    .param("id", mensaje.eventId())
                    .update();
            throw error;
        }
    }

    private void despachar(MensajeInterno mensaje) {
        switch (mensaje.tipo()) {
            case "LIBERACION_CAPACIDAD" -> {
                var datos = convertir(mensaje, DatosLiberacion.class);
                gestor.liberar(datos.idReserva(), datos.idDespacho(), datos.motivo(), ACTOR);
            }
            case "PROYECCION_DESPACHO" -> {
                var datos = convertir(mensaje, SolicitudesJornada.Proyectar.class);
                gestor.proyectar(datos.idDespacho(), datos.datos(), ACTOR);
            }
            default ->
                    throw new IllegalArgumentException(
                            "Tipo de evento interno no soportado: " + mensaje.tipo());
        }
    }

    private <T> T convertir(MensajeInterno mensaje, Class<T> tipo) {
        return json.readValue(json.writeValueAsString(mensaje.datos()), tipo);
    }

    record DatosLiberacion(UUID idReserva, UUID idDespacho, String motivo) {}
}
