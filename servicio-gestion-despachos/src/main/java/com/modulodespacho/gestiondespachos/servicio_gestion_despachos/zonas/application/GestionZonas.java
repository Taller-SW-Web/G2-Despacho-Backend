package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.CodigosError;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ConflictoZonaLog;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.OrigenDelimitacion;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.TarifaZona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.ZonaDistrito;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("!integracion-local")
public class GestionZonas {

    private static final int LONGITUD_MAXIMA_NOMBRE = 80;

    private final RepositorioZonas repositorioZonas;
    private final RepositorioDistritos repositorioDistritos;
    private final RepositorioTarifas repositorioTarifas;
    private final RegistroAuditoria auditoria;
    private final RegistroConflictos conflictos;
    private final ProveedorUsuario usuario;
    private final JsonMapper json;

    public GestionZonas(RepositorioZonas repositorioZonas, RepositorioDistritos repositorioDistritos,
            RepositorioTarifas repositorioTarifas, RegistroAuditoria auditoria, RegistroConflictos conflictos,
            ProveedorUsuario usuario, JsonMapper json) {
        this.repositorioZonas = repositorioZonas;
        this.repositorioDistritos = repositorioDistritos;
        this.repositorioTarifas = repositorioTarifas;
        this.auditoria = auditoria;
        this.conflictos = conflictos;
        this.usuario = usuario;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PaginaZonas listar(String nombre, String distrito, EstadoZona estado, int pagina, int tamano) {
        PaginaZonasCruda cruda = repositorioZonas.buscar(nombre, distrito, estado, pagina, tamano);
        List<UUID> ids = cruda.contenido().stream().map(Zona::getId).toList();
        Map<UUID, List<ZonaDistrito>> coberturas = repositorioZonas.coberturasDe(ids).stream()
                .collect(Collectors.groupingBy(cobertura -> cobertura.getZona().getId()));
        Map<UUID, TarifaZona> tarifas = repositorioTarifas.vigentesDe(ids);
        List<ZonaVista> contenido = cruda.contenido().stream()
                .map(zona -> aVista(zona, coberturas.getOrDefault(zona.getId(), List.of()),
                        tarifas.get(zona.getId())))
                .toList();
        return new PaginaZonas(contenido, cruda.pagina(), cruda.tamanoPagina(), cruda.totalElementos(),
                cruda.totalPaginas());
    }

    @Transactional(readOnly = true)
    public ZonaVista obtener(UUID id) {
        Zona zona = buscarOLanzar(id);
        List<ZonaDistrito> coberturas = repositorioZonas.coberturasDe(List.of(id));
        TarifaZona tarifa = repositorioTarifas.vigente(id).orElse(null);
        return aVista(zona, coberturas, tarifa);
    }

    @Transactional
    public ZonaVista crear(SolicitudZona solicitud) {
        DatosValidados datos = validar(solicitud);
        validarNombreUnico(datos.nombre(), null);

        UUID zonaId = UUID.randomUUID();
        List<ZonaDistrito> coberturas = construirCoberturas(datos);
        validarSolapamiento(zonaId, coberturas, datos.nombre());

        Zona zona = new Zona();
        zona.setId(zonaId);
        zona.setNombre(datos.nombre());
        zona.setEstado(solicitud.estado() == null ? EstadoZona.ACTIVO : solicitud.estado());
        zona.setOrigenDelimitacion(OrigenDelimitacion.DISTRITOS);
        UUID usuarioId = usuario.idUuid().orElse(null);
        zona.setCreadoPor(usuarioId);
        zona.setActualizadoPor(usuarioId);

        repositorioZonas.guardar(zona);
        repositorioZonas.agregarCoberturas(zona, coberturas);
        ZonaVista vista = aVista(zona, coberturas, null);
        auditoria.registrar("zona", zonaId.toString(), AccionAuditoria.CREAR, null, serializar(vista),
                usuario.identificador());
        return vista;
    }

    @Transactional
    public ZonaVista editar(UUID id, SolicitudZona solicitud) {
        Zona zona = buscarOLanzar(id);
        DatosValidados datos = validar(solicitud);
        validarNombreUnico(datos.nombre(), id);

        List<ZonaDistrito> coberturas = construirCoberturas(datos);
        validarSolapamiento(id, coberturas, datos.nombre());

        String anteriores = serializar(aVista(zona, repositorioZonas.coberturasDe(List.of(id)),
                repositorioTarifas.vigente(id).orElse(null)));
        zona.setNombre(datos.nombre());
        zona.setActualizadoPor(usuario.idUuid().orElse(null));
        repositorioZonas.guardar(zona);
        repositorioZonas.reemplazarCoberturas(id, coberturas);

        ZonaVista vista = aVista(zona, coberturas, repositorioTarifas.vigente(id).orElse(null));
        auditoria.registrar("zona", id.toString(), AccionAuditoria.MODIFICAR, anteriores, serializar(vista),
                usuario.identificador());
        return vista;
    }

    @Transactional
    public ZonaVista cambiarEstado(UUID id, EstadoZona nuevoEstado) {
        Zona zona = buscarOLanzar(id);
        List<ZonaDistrito> coberturas = repositorioZonas.coberturasDe(List.of(id));
        if (zona.getEstado() == nuevoEstado) {
            return aVista(zona, coberturas, repositorioTarifas.vigente(id).orElse(null));
        }
        if (nuevoEstado == EstadoZona.ACTIVO) {
            validarSolapamiento(id, coberturas, zona.getNombre());
        }
        String anteriores = serializar(aVista(zona, coberturas, repositorioTarifas.vigente(id).orElse(null)));
        zona.setEstado(nuevoEstado);
        zona.setActualizadoPor(usuario.idUuid().orElse(null));
        repositorioZonas.guardar(zona);

        ZonaVista vista = aVista(zona, coberturas, repositorioTarifas.vigente(id).orElse(null));
        auditoria.registrar("zona", id.toString(), AccionAuditoria.CAMBIAR_ESTADO, anteriores, serializar(vista),
                usuario.identificador());
        return vista;
    }

    @Transactional(readOnly = true)
    public ResolucionZona resolverZona(String distrito, String codigoPostal) {
        if (esVacio(distrito) && esVacio(codigoPostal)) {
            throw ErrorDeNegocio.de(400, CodigosError.CAMPOS_INVALIDOS,
                    "Debe indicar un distrito o un código postal");
        }
        return repositorioZonas.resolverCobertura(limpiar(distrito), limpiar(codigoPostal))
                .map(zona -> ResolucionZona.cubierto(zona.getId(), zona.getNombre()))
                .orElseGet(ResolucionZona::sinCobertura);
    }

    private Zona buscarOLanzar(UUID id) {
        return repositorioZonas.buscarPorId(id).orElseThrow(() -> ErrorDeNegocio.de(
                404, CodigosError.ZONA_NO_ENCONTRADA, "La zona no existe"));
    }

    private void validarNombreUnico(String nombre, UUID excluirId) {
        if (repositorioZonas.existeNombre(nombre, excluirId)) {
            conflictos.registrar(ConflictoZonaLog.de(nombre, null, "NOMBRE_DUPLICADO",
                    "Ya existe una zona con el nombre " + nombre, usuario.idUuid().orElse(null)));
            throw ErrorDeNegocio.de(409, CodigosError.ZONA_CONFLICTO, "Ya existe una zona con ese nombre",
                    List.of(new ErrorDeCampo("nombre", "El nombre ya está en uso")));
        }
    }

    private void validarSolapamiento(UUID zonaId, List<ZonaDistrito> coberturas, String nombreIntentado) {
        Set<UUID> distritos = coberturas.stream()
                .filter(cobertura -> cobertura.getDistrito() != null)
                .map(cobertura -> cobertura.getDistrito().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> codigos = coberturas.stream()
                .filter(cobertura -> cobertura.getCodigoPostal() != null)
                .map(ZonaDistrito::getCodigoPostal)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ZonaDistrito> encontrados = repositorioZonas.buscarConflicto(zonaId, distritos, codigos);
        if (!encontrados.isEmpty()) {
            Zona enConflicto = encontrados.get(0).getZona();
            conflictos.registrar(ConflictoZonaLog.de(nombreIntentado, enConflicto.getId(), "COBERTURA_SOLAPADA",
                    "La cobertura se solapa con la zona " + enConflicto.getNombre(),
                    usuario.idUuid().orElse(null)));
            throw ErrorDeNegocio.de(409, CodigosError.ZONA_CONFLICTO,
                    "La cobertura se solapa con otra zona activa",
                    List.of(new ErrorDeCampo("distritos", "Pertenece a la zona " + enConflicto.getNombre())));
        }
    }

    private DatosValidados validar(SolicitudZona solicitud) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        String nombre = solicitud.nombre() == null ? null : solicitud.nombre().trim();
        if (esVacio(nombre)) {
            errores.add(new ErrorDeCampo("nombre", "El nombre es obligatorio"));
        } else if (nombre.length() > LONGITUD_MAXIMA_NOMBRE) {
            errores.add(new ErrorDeCampo("nombre",
                    "El nombre no puede superar " + LONGITUD_MAXIMA_NOMBRE + " caracteres"));
        }
        List<String> distritos = limpiarLista(solicitud.distritos());
        List<String> codigos = limpiarLista(solicitud.codigosPostales());
        if (distritos.isEmpty() && codigos.isEmpty()) {
            errores.add(new ErrorDeCampo("distritos",
                    "Debe indicar al menos un distrito o un código postal"));
        }
        if (!errores.isEmpty()) {
            throw ErrorDeNegocio.de(400, CodigosError.CAMPOS_INVALIDOS,
                    "La solicitud contiene campos inválidos", errores);
        }
        return new DatosValidados(nombre, distritos, codigos);
    }

    private List<ZonaDistrito> construirCoberturas(DatosValidados datos) {
        Map<String, Distrito> porNombre = repositorioDistritos.porNombres(datos.distritos());
        List<ErrorDeCampo> desconocidos = datos.distritos().stream()
                .filter(nombre -> !porNombre.containsKey(nombre.toLowerCase(Locale.ROOT)))
                .map(nombre -> new ErrorDeCampo("distritos", "Distrito no reconocido: " + nombre))
                .toList();
        if (!desconocidos.isEmpty()) {
            throw ErrorDeNegocio.de(400, CodigosError.DISTRITO_NO_ENCONTRADO,
                    "Uno o más distritos no existen en el catálogo", desconocidos);
        }
        List<ZonaDistrito> coberturas = new ArrayList<>();
        datos.distritos().forEach(nombre -> {
            Distrito distrito = porNombre.get(nombre.toLowerCase(Locale.ROOT));
            coberturas.add(ZonaDistrito.deDistrito(distrito));
        });
        datos.codigosPostales().forEach(codigo -> coberturas.add(ZonaDistrito.deCodigoPostal(codigo)));
        return coberturas;
    }

    private ZonaVista aVista(Zona zona, List<ZonaDistrito> coberturas, TarifaZona tarifa) {
        List<DistritoVista> distritos = coberturas.stream()
                .filter(cobertura -> cobertura.getDistrito() != null)
                .map(cobertura -> new DistritoVista(cobertura.getDistrito().getId(),
                        cobertura.getDistrito().getNombre()))
                .sorted(Comparator.comparing(DistritoVista::nombre))
                .toList();
        List<String> codigos = coberturas.stream()
                .filter(cobertura -> cobertura.getCodigoPostal() != null)
                .map(ZonaDistrito::getCodigoPostal)
                .sorted()
                .toList();
        return new ZonaVista(zona.getId(), zona.getNombre(), zona.getEstado(), zona.getOrigenDelimitacion(),
                distritos, codigos, tarifa == null ? null : GestionTarifas.aVista(tarifa),
                zona.getCreadoEn(), zona.getActualizadoEn());
    }

    private static List<String> limpiarLista(List<String> valores) {
        if (valores == null) {
            return List.of();
        }
        return valores.stream()
                .filter(valor -> valor != null && !valor.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String limpiar(String valor) {
        return esVacio(valor) ? null : valor.trim();
    }

    private static boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private String serializar(Object valor) {
        return valor == null ? null : json.writeValueAsString(valor);
    }

    private record DatosValidados(String nombre, List<String> distritos, List<String> codigosPostales) {
    }
}
