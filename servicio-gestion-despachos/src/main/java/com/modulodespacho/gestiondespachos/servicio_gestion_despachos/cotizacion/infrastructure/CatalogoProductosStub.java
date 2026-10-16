package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.infrastructure;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ProveedorDatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;

/**
 * Stub configurable de Productos y Ofertas. Usa un catálogo local ampliable u reemplazable mediante
 * las propiedades {@code despacho.productos.stub.catalogo.*} mientras no exista la API real.
 */
@Component
@Profile("!integracion-local")
@ConfigurationProperties(prefix = "despacho.productos.stub")
public class CatalogoProductosStub implements ProveedorDatosFisicos {

    private final Map<String, ProductoStub> catalogo = new LinkedHashMap<>();

    public CatalogoProductosStub() {
        catalogo.put("POL-NEG-M", new ProductoStub(new BigDecimal("0.25"), new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal("10")));
        catalogo.put("ZAP-RUN-42", new ProductoStub(new BigDecimal("1.40"), new BigDecimal("20"),
                new BigDecimal("20"), new BigDecimal("40")));
    }

    @Override
    public Map<String, DatosFisicos> obtener(Collection<String> skus) {
        Map<String, DatosFisicos> resultado = new LinkedHashMap<>();
        for (String sku : skus) {
            ProductoStub producto = catalogo.get(sku);
            if (producto != null) {
                resultado.put(sku, new DatosFisicos(producto.getPesoKg(), producto.getLargoCm(),
                        producto.getAnchoCm(), producto.getAltoCm()));
            }
        }
        return resultado;
    }

    public Map<String, ProductoStub> getCatalogo() {
        return catalogo;
    }

    public static class ProductoStub {

        private BigDecimal pesoKg;
        private BigDecimal largoCm;
        private BigDecimal anchoCm;
        private BigDecimal altoCm;

        public ProductoStub() {
        }

        public ProductoStub(BigDecimal pesoKg, BigDecimal largoCm, BigDecimal anchoCm, BigDecimal altoCm) {
            this.pesoKg = pesoKg;
            this.largoCm = largoCm;
            this.anchoCm = anchoCm;
            this.altoCm = altoCm;
        }

        public BigDecimal getPesoKg() {
            return pesoKg;
        }

        public void setPesoKg(BigDecimal pesoKg) {
            this.pesoKg = pesoKg;
        }

        public BigDecimal getLargoCm() {
            return largoCm;
        }

        public void setLargoCm(BigDecimal largoCm) {
            this.largoCm = largoCm;
        }

        public BigDecimal getAnchoCm() {
            return anchoCm;
        }

        public void setAnchoCm(BigDecimal anchoCm) {
            this.anchoCm = anchoCm;
        }

        public BigDecimal getAltoCm() {
            return altoCm;
        }

        public void setAltoCm(BigDecimal altoCm) {
            this.altoCm = altoCm;
        }
    }
}
