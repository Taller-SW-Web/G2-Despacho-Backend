package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.CotizacionInvalidaException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.PuertoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.SolicitudCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;

/** Datos de prueba: no consulta Productos, tarifas ni zonas reales. */
@Component
@Profile("integracion-local")
public class AdaptadorCotizacionSimulada implements PuertoCotizacion {

    private record DatosFisicos(BigDecimal pesoKg, BigDecimal volumenM3) {}
    private static final Map<String, DatosFisicos> PRODUCTOS = Map.of(
            "POL-NEG-M", new DatosFisicos(new BigDecimal("0.25"), new BigDecimal("0.001")),
            "ZAP-RUN-42", new DatosFisicos(new BigDecimal("1.40"), new BigDecimal("0.016")));

    @Override
    public boolean tieneCobertura(String distrito) {
        return "San Borja".equalsIgnoreCase(distrito.trim());
    }

    @Override
    public ResultadoCotizacion cotizar(SolicitudCotizacion solicitud) {
        SolicitudCotizacion.Destino destino = solicitud.destino();
        if (!tieneCobertura(destino.distrito())) {
            return new ResultadoCotizacion(false, null, null, destino.direccion(),
                    destino.distrito(), destino.codigoPostal(), null, null, null,
                    null, null, null, null, "La dirección se encuentra fuera de nuestra zona de cobertura");
        }
        if (solicitud.lineas() == null) {
            return new ResultadoCotizacion(true, null, "Lima Centro", destino.direccion(),
                    destino.distrito(), destino.codigoPostal(), null, null, null, null, null, null, null, null);
        }
        BigDecimal peso = BigDecimal.ZERO;
        BigDecimal volumen = BigDecimal.ZERO;
        for (SolicitudCotizacion.Linea linea : solicitud.lineas()) {
            DatosFisicos producto = PRODUCTOS.get(linea.sku());
            if (producto == null) {
                throw new CotizacionInvalidaException("DESP_ERROR_PRODUCTO_NO_ENCONTRADO",
                        "El SKU no pertenece al catálogo simulado de integración", 422);
            }
            BigDecimal cantidad = BigDecimal.valueOf(linea.cantidad());
            peso = peso.add(producto.pesoKg().multiply(cantidad));
            volumen = volumen.add(producto.volumenM3().multiply(cantidad));
        }
        Instant ahora = Instant.now();
        LocalDate fecha = LocalDate.ofInstant(ahora, ZoneOffset.UTC);
        for (int dias = 0; dias < 2;) {
            fecha = fecha.plusDays(1);
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) {
                dias++;
            }
        }
        return new ResultadoCotizacion(true, "EXACTA", "Lima Centro", destino.direccion(),
                destino.distrito(), destino.codigoPostal(), peso, volumen, new BigDecimal("10.00"),
                "PEN", 2, fecha, ahora, null);
    }
}
