package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.api;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application.ValidadorGeoJson.invalido;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application.GestionGeometrias;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("!integracion-local")
public class ControladorGeometrias {
    private final GestionGeometrias gestion;
    private final JsonMapper json;
    private final int maximoBytes;

    public ControladorGeometrias(
            GestionGeometrias gestion,
            JsonMapper json,
            @Value("${despacho.geometrias.maximo-bytes:5242880}") int maximoBytes) {
        this.gestion = gestion;
        this.json = json;
        if (maximoBytes < 1 || maximoBytes == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Límite geográfico inválido");
        this.maximoBytes = maximoBytes;
    }

    @GetMapping(value = "/api/v1/distritos/geometrias", produces = "application/geo+json")
    public JsonNode capa(@RequestParam MultiValueMap<String, String> parametros) {
        parametros(parametros, Set.of("nombre", "estado"));
        return gestion.capa(
                parametros.getFirst("nombre"),
                parametros.getFirst("estado") == null ? "TODOS" : parametros.getFirst("estado"));
    }

    @GetMapping(value = "/api/v1/zonas/{id}/geometria", produces = "application/geo+json")
    public JsonNode zona(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> parametros) {
        parametros(parametros, Set.of());
        return gestion.zona(id);
    }

    @PostMapping(value = "/api/v1/distritos/geometrias/validaciones", consumes = "application/json")
    public JsonNode validar(
            HttpServletRequest peticion, @RequestParam MultiValueMap<String, String> parametros)
            throws IOException {
        parametros(parametros, Set.of());
        return gestion.validar(cuerpo(peticion));
    }

    @PostMapping(
            value = "/api/v1/distritos/geometrias/importaciones",
            consumes = "application/json")
    public JsonNode confirmar(
            HttpServletRequest peticion,
            @RequestHeader(value = "Idempotency-Key", required = false) String clave,
            @RequestParam MultiValueMap<String, String> parametros)
            throws IOException {
        parametros(parametros, Set.of());
        return gestion.confirmar(cuerpo(peticion), clave);
    }

    private JsonNode cuerpo(HttpServletRequest peticion) throws IOException {
        if (peticion.getContentLengthLong() > maximoBytes) throw demasiadoGrande();
        byte[] contenido = peticion.getInputStream().readNBytes(maximoBytes + 1);
        if (contenido.length > maximoBytes) throw demasiadoGrande();
        try {
            JsonNode nodo =
                    json.reader()
                            .with(
                                    tools.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(new String(contenido, StandardCharsets.UTF_8));
            if (nodo == null) throw invalido("El cuerpo JSON es obligatorio");
            return nodo;
        } catch (JacksonException fallo) {
            throw invalido("El cuerpo no es JSON válido");
        }
    }

    private ErrorDeNegocio demasiadoGrande() {
        return ErrorDeNegocio.de(
                413,
                "DESP_ERROR_SOLICITUD_DEMASIADO_GRANDE",
                "El cuerpo supera el límite geográfico permitido");
    }

    private void parametros(MultiValueMap<String, String> parametros, Set<String> permitidos) {
        parametros.forEach(
                (nombre, valores) -> {
                    if (!permitidos.contains(nombre) || valores.size() != 1)
                        throw invalido("Parámetro desconocido o repetido: " + nombre);
                });
    }
}
