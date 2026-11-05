package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

/** Identidad explícita del reintento, conservada atómicamente con negocio y auditoría. */
@Service
@Profile("!integracion-local")
public class ComandosConfiguracion {
    private final JdbcClient sql;
    private final JsonMapper json;
    private final ProveedorUsuario usuario;
    private final Clock reloj;
    private final TransactionTemplate transaccion;

    public ComandosConfiguracion(JdbcClient sql, JsonMapper json, ProveedorUsuario usuario,
            Clock reloj, PlatformTransactionManager transacciones) {
        this.sql = sql; this.json = json; this.usuario = usuario; this.reloj = reloj;
        this.transaccion = new TransactionTemplate(transacciones);
    }

    public <T> T ejecutar(String operacion, Object solicitud, String clave, Class<T> tipo, Supplier<T> accion) {
        if (clave == null) return accion.get();
        if (!clave.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Idempotency-Key debe ser un UUID v4");
        }
        UUID id = UUID.fromString(clave);
        String actor = usuario.identificador();
        String huella = huella(json.writeValueAsString(normalizar(json.valueToTree(solicitud))));
        return transaccion.execute(estado -> {
            sql.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identidad, 0))")
                    .param("identidad", actor + ":" + id).query((fila, indice) -> 1).single();
            var anterior = sql.sql("SELECT operacion, huella, respuesta::text FROM comandos_configuracion WHERE ejecutor_id=:actor AND clave=:clave")
                    .param("actor", actor).param("clave", id)
                    .query((fila, indice) -> new Aceptado(fila.getString(1), fila.getString(2), fila.getString(3))).optional();
            if (anterior.isPresent()) {
                if (!anterior.get().operacion().equals(operacion) || !anterior.get().huella().equals(huella)) {
                    throw ErrorDeNegocio.de(409, "DESP_ERROR_OPERACION_REUTILIZADA", "La clave ya identifica otra decisión de configuración");
                }
                return json.readValue(anterior.get().respuesta(), tipo);
            }
            T resultado = accion.get();
            sql.sql("INSERT INTO comandos_configuracion(ejecutor_id,clave,operacion,huella,respuesta,creado_en) VALUES (:actor,:clave,:operacion,:huella,CAST(:respuesta AS jsonb),:ahora)")
                    .param("actor", actor).param("clave", id).param("operacion", operacion).param("huella", huella)
                    .param("respuesta", json.writeValueAsString(resultado)).param("ahora", Timestamp.from(reloj.instant())).update();
            return resultado;
        });
    }

    private Object normalizar(JsonNode nodo) {
        if (nodo.isObject()) {
            var objeto = new java.util.TreeMap<String, Object>();
            nodo.properties().forEach(campo -> objeto.put(campo.getKey(), normalizar(campo.getValue())));
            return objeto;
        }
        if (nodo.isArray()) {
            var lista = new java.util.ArrayList<>(); nodo.forEach(valor -> lista.add(normalizar(valor))); return lista;
        }
        if (nodo.isString()) return nodo.asText().trim();
        if (nodo.isNumber()) return nodo.decimalValue().stripTrailingZeros();
        if (nodo.isBoolean()) return nodo.asBoolean();
        return null;
    }

    private static String huella(String solicitud) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(solicitud.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 no disponible", error); }
    }

    private record Aceptado(String operacion, String huella, String respuesta) {}
}
