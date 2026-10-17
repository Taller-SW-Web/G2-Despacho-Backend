package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.infrastructure;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ProveedorDatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;

public final class ProductosProgramacionHttp implements ProveedorDatosFisicos {
    private final RestClient cliente;
    private final Supplier<String> token;
    public ProductosProgramacionHttp(RestClient cliente, Supplier<String> token) { this.cliente = cliente; this.token = token; }
    @Override public Map<String, DatosFisicos> obtener(Collection<String> skus) {
        try {
            String credencial = token.get();
            if (credencial == null || credencial.isBlank()) throw noDisponible();
            Respuesta respuesta = cliente.post().uri("/api/v1/productos/datos-fisicos/consulta")
                    .headers(cabeceras -> cabeceras.setBearerAuth(credencial)).body(Map.of("skus", new LinkedHashSet<>(skus)))
                    .retrieve().onStatus(estado -> estado.value() != 200, (solicitud, resultado) -> {
                        if (resultado.getStatusCode().value() == 422) {
                            var problema = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resultado.getBody());
                            String codigo = problema == null ? "" : problema.path("code").asText();
                            if (Set.of("DESP_ERROR_PRODUCTO_NO_ENCONTRADO", "DESP_ERROR_DATOS_FISICOS_INCOMPLETOS").contains(codigo))
                                throw ErrorDeNegocio.de(422, codigo, "Un producto no existe o no tiene datos físicos válidos");
                        }
                        throw noDisponible();
                    }).body(Respuesta.class);
            if (respuesta == null || respuesta.productos() == null || respuesta.noEncontrados() == null) throw noDisponible();
            if (!respuesta.noEncontrados().isEmpty()) throw ErrorDeNegocio.de(422, "DESP_ERROR_PRODUCTO_NO_ENCONTRADO", "Uno o más productos no existen");
            Map<String, DatosFisicos> fisicos = new HashMap<>();
            for (Producto producto : respuesta.productos()) {
                if (producto == null || producto.sku() == null || !skus.contains(producto.sku()) || fisicos.containsKey(producto.sku())) throw noDisponible();
                Dimensiones dimensiones = producto.dimensionesCm();
                fisicos.put(producto.sku(), new DatosFisicos(producto.pesoKg(), dimensiones == null ? null : dimensiones.largo(),
                        dimensiones == null ? null : dimensiones.ancho(), dimensiones == null ? null : dimensiones.alto()));
            }
            return fisicos;
        } catch (ErrorDeNegocio error) { throw error; }
        catch (RuntimeException error) { throw noDisponible(); }
    }
    private static ErrorDeNegocio noDisponible() { return ErrorDeNegocio.de(503, "DESP_ERROR_PRODUCTOS_NO_DISPONIBLE", "No fue posible consultar Productos y Ofertas"); }
    public record Respuesta(List<Producto> productos, List<String> noEncontrados) {}
    public record Producto(String sku, String estado, BigDecimal pesoKg, Dimensiones dimensionesCm, String actualizadoEn) {}
    public record Dimensiones(BigDecimal largo, BigDecimal ancho, BigDecimal alto) {}
}
