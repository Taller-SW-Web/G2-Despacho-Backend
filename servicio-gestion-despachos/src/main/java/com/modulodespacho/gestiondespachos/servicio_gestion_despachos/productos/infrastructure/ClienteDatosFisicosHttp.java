package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.productos.infrastructure;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.application.ProveedorDatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.cotizacion.domain.DatosFisicos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.seguridad.DiagnosticoJwt;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.web.FiltroCorrelacion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Transporte y validación contractual compartidos; la elegibilidad comercial pertenece a F-01. */
public final class ClienteDatosFisicosHttp implements ProveedorDatosFisicos {

    private static final Logger LOG = LoggerFactory.getLogger(ClienteDatosFisicosHttp.class);
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(
                            tools.jackson.databind.DeserializationFeature
                                    .USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();
    private static final Set<String> ESTADOS = Set.of("ACTIVO", "INACTIVO", "BORRADOR");
    private final RestClient cliente;
    private final Supplier<String> token;

    public ClienteDatosFisicosHttp(RestClient cliente, Supplier<String> token) {
        this.cliente = cliente;
        this.token = token;
    }

    @Override
    public Map<String, DatosFisicos> obtener(Collection<String> skus) {
        List<String> solicitados = new ArrayList<>(new LinkedHashSet<>(skus));
        if (solicitados.size() > 100) {
            throw ErrorDeNegocio.de(
                    400,
                    CodigosError.LIMITE_SKUS_EXCEDIDO,
                    "Se admiten como máximo 100 SKU únicos por solicitud");
        }
        if (solicitados.isEmpty()
                || solicitados.stream().anyMatch(sku -> sku == null || sku.isBlank())) {
            throw ErrorDeNegocio.de(
                    400, CodigosError.SOLICITUD_INVALIDA, "Debe indicar SKU no vacíos");
        }
        String correlacion = FiltroCorrelacion.correlacionActual();
        long inicio = System.nanoTime();
        try {
            String credencial = token.get();
            if (credencial == null || credencial.isBlank()) {
                registrarFallo(correlacion, "token_de_despacho_no_disponible", null, inicio);
                throw noDisponible();
            }
            return cliente.post()
                    .uri("/api/v1/productos/datos-fisicos/consulta")
                    .headers(
                            cabeceras -> {
                                cabeceras.setBearerAuth(credencial);
                                if (correlacion != null)
                                    cabeceras.set(FiltroCorrelacion.ENCABEZADO, correlacion);
                            })
                    .body(Map.of("skus", solicitados))
                    .exchange(
                            (peticion, respuesta) -> {
                                int estado = respuesta.getStatusCode().value();
                                if (estado >= 500 || estado == 401 || estado == 403) {
                                    registrarFallo(
                                            correlacion,
                                            estado < 500
                                                    ? "credencial_rechazada"
                                                    : "error_del_proveedor",
                                            estado,
                                            inicio);
                                    throw noDisponible();
                                }
                                if (estado != 200 && estado != 422) throw invalida();
                                JsonNode cuerpo;
                                try {
                                    cuerpo = JSON.readTree(respuesta.getBody());
                                } catch (RuntimeException error) {
                                    throw invalida();
                                }
                                if (cuerpo == null || !cuerpo.isObject()) throw invalida();
                                if (estado == 422) throw traducirProblema(cuerpo, solicitados);
                                return validarLote(cuerpo, solicitados);
                            });
        } catch (ErrorDeNegocio error) {
            throw error;
        } catch (RestClientException error) {
            registrarFallo(
                    correlacion,
                    DiagnosticoJwt.tiempoAgotado(error)
                            ? "tiempo_agotado"
                            : "consulta_http_fallida",
                    null,
                    inicio);
            throw noDisponible();
        } catch (RuntimeException error) {
            registrarFallo(correlacion, "consulta_fallida", null, inicio);
            throw noDisponible();
        }
    }

    private void registrarFallo(String correlacion, String motivo, Integer estado, long inicio) {
        LOG.warn(
                "Falló la consulta física de Productos. correlacion={} motivo={} estado_productos={} duracion_ms={}",
                correlacion,
                motivo,
                estado,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
    }

    private Map<String, DatosFisicos> validarLote(JsonNode cuerpo, List<String> solicitados) {
        JsonNode productos = cuerpo.path("productos");
        JsonNode noEncontrados = cuerpo.path("noEncontrados");
        if (!productos.isArray() || !noEncontrados.isArray()) throw invalida();
        Set<String> clasificados = new LinkedHashSet<>();
        Map<String, DatosFisicos> datos = new LinkedHashMap<>();
        for (JsonNode producto : productos) {
            String sku = texto(producto.path("sku"));
            clasificar(sku, solicitados, clasificados);
            String estado = texto(producto.path("estado"));
            if (!ESTADOS.contains(estado)) throw invalida();
            Instant actualizado;
            try {
                actualizado = Instant.parse(texto(producto.path("actualizadoEn")));
            } catch (RuntimeException error) {
                throw invalida();
            }
            JsonNode dimensiones = producto.path("dimensionesCm");
            datos.put(
                    sku,
                    new DatosFisicos(
                            positivo(producto.path("pesoKg")),
                            positivo(dimensiones.path("largo")),
                            positivo(dimensiones.path("ancho")),
                            positivo(dimensiones.path("alto")),
                            estado,
                            actualizado));
        }
        Set<String> ausentes = new LinkedHashSet<>();
        for (JsonNode sku : noEncontrados) {
            String identificador = texto(sku);
            clasificar(identificador, solicitados, clasificados);
            ausentes.add(identificador);
        }
        if (clasificados.size() != solicitados.size()) throw invalida();
        if (!ausentes.isEmpty()) {
            throw ErrorDeNegocio.de(
                            422,
                            CodigosError.PRODUCTO_NO_ENCONTRADO,
                            "Uno o más productos no existen en el catálogo")
                    .conSkusAfectados(solicitados.stream().filter(ausentes::contains).toList());
        }
        Map<String, DatosFisicos> ordenados = new LinkedHashMap<>();
        solicitados.forEach(sku -> ordenados.put(sku, datos.get(sku)));
        return ordenados;
    }

    private ErrorDeNegocio traducirProblema(JsonNode problema, List<String> solicitados) {
        if (!problema.path("status").isIntegralNumber()
                || problema.path("status").intValue() != 422) {
            throw invalida();
        }
        String codigo = texto(problema.path("code"));
        // La entrada saliente ya es estructuralmente válida. Bajo D-G2-02, únicamente
        // el rechazo de bases no físicas identifica afectados en SKU_INVALIDO.
        String publico =
                switch (codigo) {
                    case "DATOS_FISICOS_INCOMPLETOS" -> CodigosError.DATOS_FISICOS_INCOMPLETOS;
                    case "SKU_INVALIDO" -> CodigosError.PRODUCTO_NO_ELEGIBLE;
                    default -> throw invalida();
                };
        JsonNode afectados = problema.path("details").path("skusAfectados");
        if (!afectados.isArray() || afectados.isEmpty() || afectados.size() > 100) throw invalida();
        List<String> identificados = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (JsonNode afectado : afectados) {
            String sku = texto(afectado);
            clasificar(sku, solicitados, vistos);
            identificados.add(sku);
        }
        List<String> ordenSolicitud = solicitados.stream().filter(vistos::contains).toList();
        if ("SKU_INVALIDO".equals(codigo) && !identificados.equals(ordenSolicitud))
            throw invalida();
        return ErrorDeNegocio.de(422, publico, "Productos rechazó los SKU identificados")
                .conSkusAfectados(identificados);
    }

    private static void clasificar(String sku, List<String> solicitados, Set<String> vistos) {
        if (!solicitados.contains(sku) || !vistos.add(sku)) throw invalida();
    }

    private static String texto(JsonNode valor) {
        if (!valor.isString() || valor.asText().isBlank()) throw invalida();
        return valor.asText();
    }

    private static BigDecimal positivo(JsonNode valor) {
        if (!valor.isNumber() || valor.decimalValue().signum() <= 0) throw invalida();
        return valor.decimalValue();
    }

    private static ErrorDeNegocio invalida() {
        return ErrorDeNegocio.de(
                502,
                CodigosError.RESPUESTA_PRODUCTOS_INVALIDA,
                "La respuesta de Productos no cumple el contrato de datos físicos");
    }

    private static ErrorDeNegocio noDisponible() {
        return ErrorDeNegocio.de(
                503,
                CodigosError.PRODUCTOS_NO_DISPONIBLE,
                "No fue posible consultar Productos y Ofertas");
    }
}
