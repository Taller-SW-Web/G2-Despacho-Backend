package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.api;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

/** Comprobar tipos JSON antes de Bean Validation evita coerciones de 1.5 o "1" a entero. */
@Component
public class SolicitudesProgramacion {
    private final Validator validador;
    public SolicitudesProgramacion(Validator validador) { this.validador = validador; }

    public SolicitudRecepcion recepcion(JsonNode cuerpo) {
        objeto(cuerpo, "solicitud", "idPedido", "destinatario", "destino", "lineas");
        Destinatario destinatario = null;
        JsonNode persona = cuerpo.get("destinatario");
        if (presente(persona)) {
            objeto(persona, "destinatario", "nombre", "telefono");
            destinatario = new Destinatario(texto(persona, "nombre"), texto(persona, "telefono"));
        }
        Destino destino = null;
        JsonNode lugar = cuerpo.get("destino");
        if (presente(lugar)) {
            objeto(lugar, "destino", "direccion", "referencia", "departamento", "provincia", "distrito", "codigoPostal", "coordenadas");
            Coordenadas coordenadas = null;
            if (presente(lugar.get("coordenadas"))) {
                JsonNode punto = lugar.get("coordenadas");
                objeto(punto, "destino.coordenadas", "latitud", "longitud");
                coordenadas = new Coordenadas(numero(punto, "latitud"), numero(punto, "longitud"));
            }
            destino = new Destino(texto(lugar, "direccion"), texto(lugar, "referencia"), texto(lugar, "departamento"),
                    texto(lugar, "provincia"), texto(lugar, "distrito"), texto(lugar, "codigoPostal"), coordenadas);
        }
        List<Linea> lineas = null;
        JsonNode productos = cuerpo.get("lineas");
        if (presente(productos)) {
            if (!productos.isArray()) throw invalido("lineas", "Debe ser un arreglo de productos");
            lineas = new ArrayList<>();
            for (int indice = 0; indice < productos.size(); indice++) {
                JsonNode linea = productos.get(indice);
                objeto(linea, "lineas[" + indice + "]", "sku", "cantidad");
                JsonNode cantidad = linea.get("cantidad");
                if (presente(cantidad) && (!cantidad.isIntegralNumber() || !cantidad.canConvertToInt()))
                    throw invalido("lineas[" + indice + "].cantidad", "La cantidad debe ser un entero positivo válido");
                lineas.add(new Linea(texto(linea, "sku"), presente(cantidad) ? cantidad.intValue() : null));
            }
        }
        return validar(new SolicitudRecepcion(texto(cuerpo, "idPedido"), destinatario, destino, lineas));
    }
    public SolicitudSimulacion simulacion(JsonNode cuerpo) {
        objeto(cuerpo, "solicitud", "idZona", "pesoKg", "volumenM3", "observaciones");
        String zona = texto(cuerpo, "idZona");
        if (zona != null && zona.isBlank()) throw invalido("idZona", "Indica una zona válida u omite el campo");
        return validar(new SolicitudSimulacion(zona, numero(cuerpo, "pesoKg"), numero(cuerpo, "volumenM3"), observacion(cuerpo, "observaciones")));
    }
    public SolicitudAsignacion asignacion(JsonNode cuerpo) {
        objeto(cuerpo, "solicitud", "idRepartidor", "idJornada", "observacion");
        return validar(new SolicitudAsignacion(texto(cuerpo, "idRepartidor"), texto(cuerpo, "idJornada"), observacion(cuerpo, "observacion")));
    }
    public String clave(String clave) {
        if (clave == null || clave.length() > 128 || !clave.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw invalido("Idempotency-Key", "Debe informar un UUID v4 válido");
        return clave.toLowerCase(Locale.ROOT);
    }
    private <T> T validar(T solicitud) {
        var errores = validador.validate(solicitud).stream().map(violacion -> {
            String restriccion = violacion.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            String mensaje = switch (restriccion) {
                case "NotNull", "NotBlank", "NotEmpty" -> "Este campo es obligatorio y no puede estar vacío";
                case "Positive" -> "Debe ser mayor que cero";
                default -> "El valor se encuentra fuera del rango permitido";
            };
            return new ErrorDeCampo(violacion.getPropertyPath().toString(), mensaje);
        }).sorted(Comparator.comparing(ErrorDeCampo::campo)).toList();
        if (!errores.isEmpty()) throw ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "Corrige los campos indicados", errores);
        return solicitud;
    }
    private static void objeto(JsonNode valor, String campo, String... permitidos) {
        if (valor == null || !valor.isObject()) throw invalido(campo, "Debe ser un objeto JSON");
        Set<String> nombres = Set.of(permitidos);
        for (var propiedad : valor.properties()) if (!nombres.contains(propiedad.getKey()))
            throw invalido(campo + "." + propiedad.getKey(), "El campo no está admitido en esta solicitud");
    }
    private static boolean presente(JsonNode valor) { return valor != null && !valor.isNull(); }
    private static String texto(JsonNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (!presente(valor)) return null;
        if (!valor.isTextual()) throw invalido(campo, "Debe ser una cadena de texto");
        return valor.asText().strip();
    }
    private static BigDecimal numero(JsonNode objeto, String campo) {
        JsonNode valor = objeto.get(campo);
        if (!presente(valor)) return null;
        if (!valor.isNumber()) throw invalido(campo, "Debe ser un número");
        return valor.decimalValue();
    }
    private static String observacion(JsonNode objeto, String campo) {
        String valor = texto(objeto, campo);
        if (valor == null || valor.isBlank()) return null;
        if (valor.codePointCount(0, valor.length()) > 500) throw invalido(campo, "No puede superar 500 caracteres");
        return valor;
    }
    static ErrorDeNegocio invalido(String campo, String mensaje) {
        return ErrorDeNegocio.de(400, "DESP_ERROR_CAMPOS_INVALIDOS", "La solicitud contiene datos inválidos", List.of(new ErrorDeCampo(campo, mensaje)));
    }
}
