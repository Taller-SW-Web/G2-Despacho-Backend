package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ProveedorDatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.Despacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain.EstadoDespacho;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.application.ModelosProgramacion.*;

@Service
@Profile("!integracion-local")
public class RecibirSolicitudDespacho {
    private final AlmacenProgramacion almacen;
    private final CoberturaProgramacion cobertura;
    private final ProveedorDatosFisicos productos;
    private final JsonMapper json;
    private final Clock reloj;

    public RecibirSolicitudDespacho(AlmacenProgramacion almacen, CoberturaProgramacion cobertura,
            @org.springframework.beans.factory.annotation.Qualifier("productosProgramacion") ProveedorDatosFisicos productos, JsonMapper json, Clock reloj) {
        this.almacen = almacen; this.cobertura = cobertura; this.productos = productos; this.json = json; this.reloj = reloj;
    }

    public ResultadoCreacion ejecutar(SolicitudRecepcion solicitud, String clave, String actor) {
        String huella = huella(json.writeValueAsString(solicitud));
        var existente = almacen.recuperarCreacion(clave, huella, solicitud.idPedido(), actor);
        if (existente.isPresent()) return existente.get();
        ZonaDisponible zona = cobertura.resolver(solicitud.destino().distrito(), solicitud.destino().codigoPostal());
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        try {
            solicitud.lineas().forEach(linea -> cantidades.merge(linea.sku(), linea.cantidad(), Math::addExact));
        } catch (ArithmeticException error) {
            throw ErrorDeNegocio.de(400, "DESP_ERROR_CANTIDAD_INVALIDA", "La cantidad acumulada de un producto es demasiado grande");
        }
        Map<String, DatosFisicos> fisicos = productos.obtener(cantidades.keySet());
        BigDecimal peso = BigDecimal.ZERO;
        BigDecimal volumen = BigDecimal.ZERO;
        for (var linea : cantidades.entrySet()) {
            DatosFisicos dato = fisicos.get(linea.getKey());
            if (dato == null) throw ErrorDeNegocio.de(422, "DESP_ERROR_PRODUCTO_NO_ENCONTRADO", "Uno o más productos no existen");
            if (!dato.completo()) throw ErrorDeNegocio.de(422, "DESP_ERROR_DATOS_FISICOS_INCOMPLETOS", "Un producto no tiene peso o dimensiones válidas");
            BigDecimal cantidad = BigDecimal.valueOf(linea.getValue());
            peso = peso.add(dato.pesoKg().multiply(cantidad));
            volumen = volumen.add(dato.largoCm().multiply(dato.anchoCm()).multiply(dato.altoCm())
                    .movePointLeft(6).multiply(cantidad));
        }
        Instant ahora = reloj.instant();
        Despacho despacho = nuevo(almacen.siguienteCodigo(), false, zona.id(), peso, volumen, ahora);
        despacho.setIdPedido(solicitud.idPedido());
        despacho.setDestinatarioNombre(solicitud.destinatario().nombre());
        despacho.setDestinatarioTelefono(solicitud.destinatario().telefono());
        Destino destino = solicitud.destino();
        despacho.setDireccion(destino.direccion()); despacho.setReferencia(destino.referencia());
        despacho.setDepartamento(destino.departamento()); despacho.setProvincia(destino.provincia());
        despacho.setDistrito(destino.distrito()); despacho.setCodigoPostal(destino.codigoPostal());
        if (destino.coordenadas() != null) {
            despacho.setLatitud(destino.coordenadas().latitud()); despacho.setLongitud(destino.coordenadas().longitud());
        }
        despacho.setFechaProgramada(fechaProgramada(ahora, zona.plazoDiasHabiles()));
        return almacen.crear(despacho, clave, huella, actor, null);
    }

    static Despacho nuevo(long codigo, boolean simulado, UUID zona, BigDecimal peso, BigDecimal volumen, Instant ahora) {
        Despacho despacho = new Despacho();
        despacho.setId(UUID.randomUUID());
        String sufijo = (simulado ? "SIM-" : "") + String.format(java.util.Locale.ROOT, "%06d", codigo);
        despacho.setCodigoOperativo("DSP-" + sufijo); despacho.setCodigoRastreoInterno("TRK-" + sufijo);
        despacho.setEsSimulacion(simulado); despacho.setEstado(EstadoDespacho.PENDIENTE_ASIGNACION);
        despacho.setIdZona(zona); despacho.setPesoKg(peso); despacho.setVolumenM3(volumen);
        despacho.setCreadoEn(ahora); despacho.setActualizadoEn(ahora); despacho.setEnColaDesde(ahora);
        return despacho;
    }

    public static LocalDate fechaProgramada(Instant ahora, int diasHabiles) {
        LocalDate fecha = LocalDate.ofInstant(ahora, ZoneId.of("America/Lima"));
        for (int restantes = diasHabiles; restantes > 0;) {
            fecha = fecha.plusDays(1);
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) restantes--;
        }
        return fecha;
    }

    private static String huella(String contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 no disponible", error); }
    }
}
