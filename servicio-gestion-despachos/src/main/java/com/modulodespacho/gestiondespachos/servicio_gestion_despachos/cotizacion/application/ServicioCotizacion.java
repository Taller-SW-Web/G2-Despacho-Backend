package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.CalculadoraCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionTarifas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ResolucionZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaVista;

@Service
@Profile("!integracion-local")
public class ServicioCotizacion {

    private static final String MENSAJE_SIN_COBERTURA =
            "La dirección se encuentra fuera de nuestra zona de cobertura";
    private static final ZoneId ZONA_HORARIA = ZoneId.of("America/Lima");

    private final GestionZonas gestionZonas;
    private final GestionTarifas gestionTarifas;
    private final ProveedorDatosFisicos productos;

    public ServicioCotizacion(GestionZonas gestionZonas, GestionTarifas gestionTarifas,
            ProveedorDatosFisicos productos) {
        this.gestionZonas = gestionZonas;
        this.gestionTarifas = gestionTarifas;
        this.productos = productos;
    }

    public ResultadoCotizacion cotizar(SolicitudCotizacion solicitud) {
        SolicitudCotizacion.Destino destino = solicitud.destino();
        if (destino == null || esVacio(destino.distrito())) {
            throw ErrorDeNegocio.de(400, CodigosError.DESTINO_REQUERIDO,
                    "Debe informar el distrito del destino");
        }
        ResolucionZona cobertura = gestionZonas.resolverZona(destino.distrito(), destino.codigoPostal());
        if (!cobertura.coberturaDisponible()) {
            return sinCobertura(destino);
        }
        if (solicitud.lineas() == null) {
            return soloCobertura(destino, cobertura);
        }
        validarLineas(solicitud.lineas());

        Optional<TarifaVista> tarifa = gestionTarifas.vigente(cobertura.idZona());
        if (tarifa.isEmpty()) {
            return soloCobertura(destino, cobertura);
        }

        List<CalculadoraCotizacion.ItemCalculo> items = resolverItems(solicitud.lineas());
        CalculadoraCotizacion.Totales totales = CalculadoraCotizacion.calcular(items,
                tarifa.get().factorVolumetrico());
        BigDecimal costo = CalculadoraCotizacion.costo(tarifa.get().tarifaBase(),
                tarifa.get().recargoKgAdicional(), tarifa.get().pesoIncluidoKg(), totales.pesoCotizableKg());
        Integer plazo = tarifa.get().plazoEstimadoDiasHabiles();
        Instant ahora = Instant.now();
        String moneda = tarifa.get().moneda() == null ? "PEN" : tarifa.get().moneda();

        return new ResultadoCotizacion(true, "EXACTA", cobertura.nombreZona(), destino.direccion(),
                destino.distrito(), destino.codigoPostal(), totales.pesoTotalKg(), totales.volumenTotalM3(),
                costo, moneda, plazo, fechaEstimada(plazo, ahora), ahora, null);
    }

    private void validarLineas(List<SolicitudCotizacion.Linea> lineas) {
        if (lineas.isEmpty()) {
            throw ErrorDeNegocio.de(400, CodigosError.LINEAS_VACIAS,
                    "Las líneas deben contener al menos un producto");
        }
        for (SolicitudCotizacion.Linea linea : lineas) {
            if (linea == null || esVacio(linea.sku())) {
                throw ErrorDeNegocio.de(400, CodigosError.SOLICITUD_INVALIDA,
                        "Cada línea debe indicar un SKU");
            }
            if (linea.cantidad() == null || linea.cantidad() <= 0) {
                throw ErrorDeNegocio.de(400, CodigosError.CANTIDAD_INVALIDA,
                        "La cantidad debe ser mayor que cero");
            }
        }
    }

    private List<CalculadoraCotizacion.ItemCalculo> resolverItems(List<SolicitudCotizacion.Linea> lineas) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        for (SolicitudCotizacion.Linea linea : lineas) {
            cantidades.merge(linea.sku(), linea.cantidad(), Integer::sum);
        }
        Set<String> skus = new LinkedHashSet<>(cantidades.keySet());
        Map<String, DatosFisicos> fisicos = productos.obtener(skus);

        List<ErrorDeCampo> noEncontrados = skus.stream()
                .filter(sku -> !fisicos.containsKey(sku))
                .map(sku -> new ErrorDeCampo("sku", "Producto no encontrado: " + sku))
                .toList();
        if (!noEncontrados.isEmpty()) {
            throw ErrorDeNegocio.de(422, CodigosError.PRODUCTO_NO_ENCONTRADO,
                    "Uno o más productos no existen en el catálogo", noEncontrados);
        }
        List<ErrorDeCampo> incompletos = skus.stream()
                .filter(sku -> !fisicos.get(sku).completo())
                .map(sku -> new ErrorDeCampo("sku", "Datos físicos incompletos para: " + sku))
                .toList();
        if (!incompletos.isEmpty()) {
            throw ErrorDeNegocio.de(422, CodigosError.DATOS_FISICOS_INCOMPLETOS,
                    "Uno o más productos no tienen peso o dimensiones válidas", incompletos);
        }
        List<CalculadoraCotizacion.ItemCalculo> items = new ArrayList<>();
        cantidades.forEach((sku, cantidad) -> items.add(
                new CalculadoraCotizacion.ItemCalculo(fisicos.get(sku), cantidad)));
        return items;
    }

    private ResultadoCotizacion sinCobertura(SolicitudCotizacion.Destino destino) {
        return new ResultadoCotizacion(false, null, null, destino.direccion(), destino.distrito(),
                destino.codigoPostal(), null, null, null, null, null, null, Instant.now(),
                MENSAJE_SIN_COBERTURA);
    }

    private ResultadoCotizacion soloCobertura(SolicitudCotizacion.Destino destino, ResolucionZona cobertura) {
        return new ResultadoCotizacion(true, null, cobertura.nombreZona(), destino.direccion(),
                destino.distrito(), destino.codigoPostal(), null, null, null, null, null, null,
                Instant.now(), null);
    }

    private LocalDate fechaEstimada(Integer plazoEstimado, Instant ahora) {
        LocalDate fecha = LocalDate.ofInstant(ahora, ZONA_HORARIA);
        int restantes = plazoEstimado == null ? 0 : Math.max(0, plazoEstimado);
        while (restantes > 0) {
            fecha = fecha.plusDays(1);
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) {
                restantes--;
            }
        }
        return fecha;
    }

    private static boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
