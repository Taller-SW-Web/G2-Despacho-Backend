package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.RedondeoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ProveedorUsuario;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.CalculadoraCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.ResultadoCotizacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionTarifas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.GestionZonas;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ResolucionZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.TarifaAplicada;

@Service
@Profile("!integracion-local")
public class ServicioCotizacion {

    private static final String MENSAJE_SIN_COBERTURA =
            "La dirección se encuentra fuera de nuestra zona de cobertura";
    private static final ZoneId ZONA_HORARIA = ZoneId.of("America/Lima");

    private final GestionZonas gestionZonas;
    private final GestionTarifas gestionTarifas;
    private final ProveedorDatosFisicos productos;
    private final RegistroCotizaciones registro;
    private final ProveedorUsuario usuario;
    private final Clock reloj;
    private final boolean simulado;

    public ServicioCotizacion(GestionZonas gestionZonas, GestionTarifas gestionTarifas,
            ProveedorDatosFisicos productos, RegistroCotizaciones registro, ProveedorUsuario usuario,
            Clock reloj, Environment entorno) {
        this.gestionZonas = gestionZonas;
        this.gestionTarifas = gestionTarifas;
        this.productos = productos;
        this.registro = registro;
        this.usuario = usuario;
        this.reloj = reloj;
        this.simulado = entorno.matchesProfiles("productos-simulados");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        Map<String, Integer> cantidades = agrupar(solicitud.lineas());

        Optional<TarifaAplicada> capturada = gestionTarifas.capturar(cobertura.idZona());
        if (capturada.isEmpty()) {
            return soloCobertura(destino, cobertura);
        }

        var tarifa = capturada.get().condiciones();
        List<InstantaneaCotizacion.LineaCotizada> lineas = resolverItems(cantidades);
        List<CalculadoraCotizacion.ItemCalculo> items = lineas.stream()
                .map(linea -> new CalculadoraCotizacion.ItemCalculo(linea.perfil(), linea.cantidad())).toList();
        CalculadoraCotizacion.Totales totales = CalculadoraCotizacion.calcular(items,
                tarifa.factorVolumetrico());
        BigDecimal costoExacto = CalculadoraCotizacion.costo(tarifa.tarifaBase(),
                tarifa.recargoKgAdicional(), tarifa.pesoIncluidoKg(), totales.pesoCotizableKg());
        BigDecimal costo = RedondeoCotizacion.costo(costoExacto);
        Integer plazo = tarifa.plazoEstimadoDiasHabiles();
        Instant ahora = reloj.instant();
        String moneda = tarifa.moneda() == null ? "PEN" : tarifa.moneda();

        ResultadoCotizacion resultado = new ResultadoCotizacion(true, "EXACTA", cobertura.nombreZona(), destino.direccion(),
                destino.distrito(), destino.codigoPostal(), totales.pesoTotalKg(), RedondeoCotizacion.volumen(totales.volumenTotalM3()),
                costo, moneda, plazo, fechaEstimada(plazo, ahora), ahora, null);
        registro.guardar(new InstantaneaCotizacion(cobertura.idZona(), capturada.get(), destino, lineas,
                totales, costoExacto, costoExacto.subtract(tarifa.tarifaBase()), costo.subtract(costoExacto),
                resultado, usuario.identificador(), simulado));
        return resultado;
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

    private Map<String, Integer> agrupar(List<SolicitudCotizacion.Linea> lineas) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        try {
            for (SolicitudCotizacion.Linea linea : lineas) {
                cantidades.merge(linea.sku(), linea.cantidad(), Math::addExact);
            }
        } catch (ArithmeticException error) {
            throw ErrorDeNegocio.de(400, CodigosError.CANTIDAD_INVALIDA,
                    "La cantidad acumulada de un producto es demasiado grande");
        }
        if (cantidades.size() > 100) {
            throw ErrorDeNegocio.de(400, CodigosError.LIMITE_SKUS_EXCEDIDO,
                    "Se admiten como máximo 100 SKU únicos por solicitud");
        }
        return cantidades;
    }

    private List<InstantaneaCotizacion.LineaCotizada> resolverItems(Map<String, Integer> cantidades) {
        Set<String> skus = new LinkedHashSet<>(cantidades.keySet());
        Map<String, DatosFisicos> fisicos = productos.obtener(skus);

        if (fisicos == null || !fisicos.keySet().equals(skus) || fisicos.values().stream().anyMatch(dato ->
                dato == null || !dato.completo() || dato.actualizadoEn() == null
                || dato.estado() == null || !Set.of("ACTIVO", "INACTIVO", "BORRADOR").contains(dato.estado()))) {
            throw ErrorDeNegocio.de(502, CodigosError.RESPUESTA_PRODUCTOS_INVALIDA,
                    "El lote físico recibido está incompleto o no cumple el contrato");
        }
        List<String> noElegibles = skus.stream()
                .filter(sku -> !"ACTIVO".equals(fisicos.get(sku).estado()))
                .toList();
        if (!noElegibles.isEmpty()) {
            throw ErrorDeNegocio.de(422, CodigosError.PRODUCTO_NO_ELEGIBLE,
                    "Uno o más productos no están habilitados para cotizar").conSkusAfectados(noElegibles);
        }
        return cantidades.entrySet().stream().map(linea -> new InstantaneaCotizacion.LineaCotizada(
                linea.getKey(), linea.getValue(), fisicos.get(linea.getKey()))).toList();
    }

    private ResultadoCotizacion sinCobertura(SolicitudCotizacion.Destino destino) {
        return new ResultadoCotizacion(false, null, null, destino.direccion(), destino.distrito(),
                destino.codigoPostal(), null, null, null, null, null, null, reloj.instant(),
                MENSAJE_SIN_COBERTURA);
    }

    private ResultadoCotizacion soloCobertura(SolicitudCotizacion.Destino destino, ResolucionZona cobertura) {
        return new ResultadoCotizacion(true, null, cobertura.nombreZona(), destino.direccion(),
                destino.distrito(), destino.codigoPostal(), null, null, null, null, null, null,
                reloj.instant(), null);
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
