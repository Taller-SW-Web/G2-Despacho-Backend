package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application;

import static com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application.ValidadorGeoJson.invalido;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.geometrias.application.RepositorioGeometrias.DistritoGeografico;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ComandosConfiguracion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.ProveedorUsuario;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RegistroAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioZonas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Profile("!integracion-local")
public class GestionGeometrias {
    private final RepositorioGeometrias repositorio;
    private final RepositorioZonas zonas;
    private final ValidadorGeoJson validador;
    private final ComandosConfiguracion comandos;
    private final ProveedorUsuario usuario;
    private final RegistroAuditoria auditoria;
    private final JsonMapper json;
    private final Clock reloj;

    public GestionGeometrias(
            RepositorioGeometrias repositorio,
            RepositorioZonas zonas,
            ValidadorGeoJson validador,
            ComandosConfiguracion comandos,
            ProveedorUsuario usuario,
            RegistroAuditoria auditoria,
            JsonMapper json,
            Clock reloj) {
        this.repositorio = repositorio;
        this.zonas = zonas;
        this.validador = validador;
        this.comandos = comandos;
        this.usuario = usuario;
        this.auditoria = auditoria;
        this.json = json;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public JsonNode capa(String nombre, String estado) {
        if (!Set.of("TODOS", "ACTIVO", "INACTIVO").contains(estado))
            throw invalido("Estado de filtro inválido");
        return coleccion(
                repositorio.catalogo(nombre, estado, null).stream()
                        .map(DistritoGeografico::entidad)
                        .toList());
    }

    @Transactional(readOnly = true)
    public JsonNode zona(UUID id) {
        JsonNode zona =
                repositorio
                        .zona(id)
                        .orElseThrow(
                                () ->
                                        ErrorDeNegocio.de(
                                                404,
                                                "DESP_ERROR_ZONA_NO_ENCONTRADA",
                                                "La zona no existe"));
        ObjectNode capa =
                coleccion(
                        repositorio.catalogo(null, "TODOS", id).stream()
                                .map(DistritoGeografico::entidad)
                                .toList());
        capa.set("zona", zona);
        return capa;
    }

    @Transactional
    public JsonNode validar(JsonNode solicitud) {
        campos(
                solicitud,
                Set.of("geojson", "fuente", "versionFuente", "correspondencias", "nombreArchivo"));
        String fuente = texto(solicitud, "fuente");
        String revision = texto(solicitud, "versionFuente");
        String archivo = null;
        if (solicitud.has("nombreArchivo")) {
            JsonNode nombre = solicitud.path("nombreArchivo");
            if (!nombre.isString()
                    || nombre.asText().trim().isEmpty()
                    || nombre.asText().trim().length() > 255
                    || nombre.asText().contains("/")
                    || nombre.asText().contains("\\")
                    || nombre.asText().chars().anyMatch(Character::isISOControl))
                throw invalido(
                        "nombreArchivo debe ser un nombre de 1 a 255 caracteres, sin rutas ni controles");
            archivo = nombre.asText().trim();
        }
        JsonNode geojson = solicitud.path("geojson");
        List<ErrorDeCampo> errores = new ArrayList<>();
        List<JsonNode> sugerencias = new ArrayList<>();
        List<JsonNode> propuestas = new ArrayList<>();
        List<JsonNode> actuales = new ArrayList<>();
        List<JsonNode> distritos = new ArrayList<>();
        ObjectNode versiones = json.createObjectNode();
        List<DistritoGeografico> catalogo = repositorio.catalogo(null, "TODOS", null);
        Map<String, DistritoGeografico> porUbigeo = new HashMap<>();
        catalogo.forEach(
                d -> porUbigeo.put(d.entidad().path("properties").path("ubigeo").asText(), d));
        Map<Integer, String> correspondencias =
                correspondencias(solicitud.path("correspondencias"));
        Set<String> usados = new HashSet<>();
        JsonNode entidades = geojson.path("features");
        int posiciones = 0;
        if (!geojson.isObject()
                || !"FeatureCollection".equals(geojson.path("type").asText())
                || !entidades.isArray()
                || entidades.isEmpty()
                || entidades.size() > 100) {
            errores.add(
                    new ErrorDeCampo(
                            "geojson", "Se requiere FeatureCollection con 1 a 100 entidades"));
        } else {
            try {
                validador.validarCrs(geojson);
            } catch (ErrorDeNegocio fallo) {
                errores.add(new ErrorDeCampo("geojson.crs", fallo.getMessage()));
            }
            for (Integer indice : correspondencias.keySet())
                if (indice >= entidades.size())
                    throw invalido("Índice de correspondencia fuera del archivo");
            for (int indice = 0; indice < entidades.size(); indice++) {
                JsonNode entidad = entidades.get(indice);
                String campo = "features[" + indice + "]";
                try {
                    if (!"Feature".equals(entidad.path("type").asText())
                            || !entidad.path("properties").isObject())
                        throw invalido("Se requiere Feature con propiedades de identificación");
                    validador.validarCrs(entidad);
                    JsonNode propiedades = entidad.path("properties");
                    String codigo;
                    if (propiedades.has("ubigeo")) {
                        if (!propiedades.path("ubigeo").isString()
                                || !propiedades.path("ubigeo").asText().matches("[0-9]{6}"))
                            throw invalido("El ubigeo debe ser texto de seis dígitos");
                        if (correspondencias.containsKey(indice))
                            throw invalido(
                                    "No se reemplaza el ubigeo declarado mediante correspondencias");
                        codigo = propiedades.path("ubigeo").asText();
                    } else {
                        codigo = correspondencias.get(indice);
                        if (codigo == null) {
                            for (DistritoGeografico candidato : catalogo) {
                                JsonNode p = candidato.entidad().path("properties");
                                String nombre =
                                        propiedades
                                                .path("nombre")
                                                .asText(propiedades.path("distrito").asText(""));
                                if (normalizar(nombre).equals(normalizar(p.path("nombre").asText()))
                                        && normalizar(propiedades.path("provincia").asText(""))
                                                .equals(normalizar(p.path("provincia").asText()))
                                        && normalizar(propiedades.path("departamento").asText(""))
                                                .equals(
                                                        normalizar(
                                                                p.path("departamento").asText())))
                                    sugerencias.add(
                                            json.valueToTree(
                                                    Map.of(
                                                            "indice",
                                                            indice,
                                                            "nombre",
                                                            p.path("nombre").asText(),
                                                            "ubigeo",
                                                            p.path("ubigeo").asText())));
                            }
                            throw invalido(
                                    "Falta ubigeo: confirma explícitamente la correspondencia");
                        }
                    }
                    DistritoGeografico distrito = porUbigeo.get(codigo);
                    if (distrito == null)
                        throw invalido("El ubigeo no pertenece al catálogo existente");
                    if (!usados.add(codigo)) throw invalido("El distrito o ubigeo está repetido");
                    JsonNode geometria = entidad.path("geometry");
                    posiciones += validador.validarGeometria(geometria);
                    validador.limitar(posiciones);
                    String motivo = repositorio.motivoInvalidez(geometria);
                    if (!motivo.isEmpty()) throw invalido("Geometría inválida: " + motivo);
                    ObjectNode propuesta = ((ObjectNode) distrito.entidad()).deepCopy();
                    propuesta.set("geometry", geometria);
                    propuestas.add(propuesta);
                    actuales.add(distrito.entidad());
                    versiones.put(distrito.id().toString(), distrito.versionDistrito());
                    ObjectNode detalle =
                            ((ObjectNode) distrito.entidad().path("properties")).deepCopy();
                    detalle.put(
                            "accion",
                            distrito.entidad().path("geometry").isNull()
                                    ? "PRIMERA_CARGA"
                                    : "REEMPLAZO");
                    distritos.add(detalle);
                } catch (ErrorDeNegocio fallo) {
                    errores.add(new ErrorDeCampo(campo, fallo.getMessage()));
                }
            }
        }
        boolean valida = errores.isEmpty();
        UUID id = valida ? UUID.randomUUID() : null;
        var vence = valida ? reloj.instant().plus(Duration.ofMinutes(30)) : null;
        ObjectNode informe = json.createObjectNode();
        informe.put("valida", valida);
        if (id == null) {
            informe.putNull("validacionId");
            informe.putNull("expiraEn");
        } else {
            informe.put("validacionId", id.toString());
            informe.put("expiraEn", vence.toString());
        }
        informe.put("fuente", fuente).put("versionFuente", revision);
        informe.put("nombreArchivo", archivo);
        informe.put("entidades", entidades.isArray() ? entidades.size() : 0)
                .put("posiciones", posiciones);
        informe.set("errores", json.valueToTree(errores));
        informe.set("sugerencias", json.valueToTree(sugerencias));
        informe.set("distritos", json.valueToTree(distritos));
        informe.set("actuales", coleccion(actuales));
        informe.set("propuesta", coleccion(propuestas));
        if (valida) {
            ObjectNode guardado = json.createObjectNode();
            guardado.set("informe", informe);
            guardado.set("versionesDistrito", versiones);
            repositorio.guardarValidacion(id, usuario.identificador(), vence, guardado);
        }
        return informe;
    }

    public JsonNode confirmar(JsonNode solicitud, String clave) {
        campos(solicitud, Set.of("validacionId"));
        UUID validacion;
        try {
            if (!solicitud.path("validacionId").isString()) throw new IllegalArgumentException();
            validacion = UUID.fromString(solicitud.path("validacionId").asText());
        } catch (IllegalArgumentException fallo) {
            throw invalido("Indica un validacionId UUID válido");
        }
        if (clave == null
                || !clave.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
            throw invalido("Idempotency-Key es obligatorio y debe ser UUID v4");
        try {
            return comandos.ejecutar(
                    "geometrias:importar",
                    solicitud,
                    clave,
                    JsonNode.class,
                    () -> aplicar(validacion));
        } catch (ErrorDeNegocio fallo) {
            throw fallo;
        } catch (RuntimeException fallo) {
            org.slf4j.LoggerFactory.getLogger(GestionGeometrias.class)
                    .error("No se pudo confirmar la importación geográfica {}", validacion, fallo);
            throw ErrorDeNegocio.de(
                    500,
                    "DESP_ERROR_PERSISTENCIA_GEOMETRIA",
                    "No se confirmó la importación; reintenta con la misma clave para recuperar su resultado");
        }
    }

    private JsonNode aplicar(UUID id) {
        zonas.bloquearConfiguracion();
        String ejecutor = usuario.identificador();
        var validacion =
                repositorio
                        .buscarValidacion(id, ejecutor)
                        .orElseThrow(
                                () ->
                                        ErrorDeNegocio.de(
                                                404,
                                                "DESP_ERROR_VALIDACION_NO_ENCONTRADA",
                                                "No existe una validación propia con ese identificador"));
        if (validacion.consumida())
            throw ErrorDeNegocio.de(
                    409,
                    "DESP_ERROR_VALIDACION_CONSUMIDA",
                    "La validación ya fue importada; recupera la respuesta con la clave original");
        if (!reloj.instant().isBefore(validacion.expiraEn()))
            throw ErrorDeNegocio.de(
                    409,
                    "DESP_ERROR_VALIDACION_EXPIRADA",
                    "La validación expiró; vuelve a revisar el archivo");
        JsonNode informe = validacion.propuesta().path("informe");
        Map<String, DistritoGeografico> vigentes = new HashMap<>();
        repositorio.catalogo(null, "TODOS", null).forEach(d -> vigentes.put(d.id().toString(), d));
        for (JsonNode propuesta : informe.path("propuesta").path("features")) {
            String distritoId = propuesta.path("properties").path("id").asText();
            DistritoGeografico actual = vigentes.get(distritoId);
            if (actual == null
                    || actual.versionDistrito()
                            != validacion
                                    .propuesta()
                                    .path("versionesDistrito")
                                    .path(distritoId)
                                    .asLong(-1)
                    || actual.entidad().path("properties").path("versionGeometria").asLong()
                            != propuesta.path("properties").path("versionGeometria").asLong())
                throw ErrorDeNegocio.de(
                        409,
                        "DESP_ERROR_GEOMETRIA_DESACTUALIZADA",
                        "El distrito o su geometría cambiaron; vuelve a validar");
            validador.validarGeometria(propuesta.path("geometry"));
            if (!repositorio.motivoInvalidez(propuesta.path("geometry")).isEmpty())
                throw invalido("La geometría ya no supera la validación topológica");
        }
        UUID importacion = UUID.randomUUID();
        var ahora = reloj.instant();
        String fuente = informe.path("fuente").asText();
        String revision = informe.path("versionFuente").asText();
        repositorio.registrarImportacion(
                importacion,
                id,
                fuente,
                revision,
                informe.path("nombreArchivo").asText(null),
                ejecutor,
                ahora);
        List<Object> guardados = new ArrayList<>();
        for (JsonNode propuesta : informe.path("propuesta").path("features")) {
            JsonNode p = propuesta.path("properties");
            UUID distrito = UUID.fromString(p.path("id").asText());
            long version = p.path("versionGeometria").asLong() + 1;
            JsonNode geometria = propuesta.path("geometry");
            repositorio.guardarGeometria(
                    distrito,
                    geometria,
                    version,
                    fuente,
                    revision,
                    importacion,
                    ejecutor,
                    ahora,
                    huella(json.writeValueAsString(geometria)));
            auditoria.registrar(
                    "geometria_distrito",
                    distrito.toString(),
                    AccionAuditoria.MODIFICAR,
                    json.writeValueAsString(vigentes.get(distrito.toString()).entidad()),
                    json.writeValueAsString(
                            Map.of("versionGeometria", version, "importacionId", importacion)),
                    ejecutor);
            guardados.add(
                    Map.of(
                            "id",
                            distrito,
                            "ubigeo",
                            p.path("ubigeo").asText(),
                            "versionGeometria",
                            version));
        }
        ObjectNode resultado =
                (ObjectNode)
                        json.valueToTree(
                                Map.of(
                                        "importacionId",
                                        importacion,
                                        "validacionId",
                                        id,
                                        "importadoEn",
                                        ahora.toString(),
                                        "importadoPor",
                                        ejecutor,
                                        "fuente",
                                        fuente,
                                        "versionFuente",
                                        revision,
                                        "distritos",
                                        guardados));
        resultado.set("nombreArchivo", informe.path("nombreArchivo"));
        return resultado;
    }

    private Map<Integer, String> correspondencias(JsonNode nodo) {
        Map<Integer, String> resultado = new HashMap<>();
        Set<String> codigos = new HashSet<>();
        if (nodo.isMissingNode()) return resultado;
        if (!nodo.isArray() || nodo.size() > 100)
            throw invalido("Correspondencias debe ser un arreglo de hasta 100 entradas");
        for (JsonNode entrada : nodo) {
            campos(entrada, Set.of("indice", "ubigeo"));
            if (!entrada.path("indice").isIntegralNumber()
                    || !entrada.path("indice").canConvertToInt()
                    || entrada.path("indice").asInt() < 0
                    || !entrada.path("ubigeo").isString()
                    || !entrada.path("ubigeo").asText().matches("[0-9]{6}"))
                throw invalido("Correspondencia de índice/ubigeo inválida");
            if (resultado.put(entrada.path("indice").asInt(), entrada.path("ubigeo").asText())
                            != null
                    || !codigos.add(entrada.path("ubigeo").asText()))
                throw invalido("Correspondencias repetidas");
        }
        return resultado;
    }

    public static void campos(JsonNode nodo, Set<String> permitidos) {
        if (!nodo.isObject()) throw invalido("Se requiere un objeto JSON");
        for (var campo : nodo.properties())
            if (!permitidos.contains(campo.getKey()))
                throw invalido("Campo no permitido: " + campo.getKey());
    }

    private String texto(JsonNode nodo, String campo) {
        if (!nodo.path(campo).isString()) throw invalido("Indica " + campo + " como texto");
        String valor = nodo.path(campo).asText().trim();
        if (valor.isEmpty() || valor.length() > 200)
            throw invalido(campo + " debe contener entre 1 y 200 caracteres");
        return valor;
    }

    private ObjectNode coleccion(List<JsonNode> entidades) {
        ObjectNode capa = json.createObjectNode().put("type", "FeatureCollection");
        capa.set("features", json.valueToTree(entidades));
        return capa;
    }

    private static String normalizar(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private static String huella(String contenido) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(contenido.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException fallo) {
            throw new IllegalStateException("SHA-256 no disponible", fallo);
        }
    }
}
