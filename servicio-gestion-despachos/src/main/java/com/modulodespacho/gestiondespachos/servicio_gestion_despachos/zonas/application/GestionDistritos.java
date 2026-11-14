package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application;

import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.auditoria.AccionAuditoria;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeCampo;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.shared.errores.ErrorDeNegocio;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.EstadoDistrito;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("!integracion-local")
public class GestionDistritos {
    private final RepositorioDistritos distritos;
    private final RepositorioZonas zonas;
    private final RegistroAuditoria auditoria;
    private final ProveedorUsuario usuario;
    private final JsonMapper json;

    public GestionDistritos(
            RepositorioDistritos distritos,
            RepositorioZonas zonas,
            RegistroAuditoria auditoria,
            ProveedorUsuario usuario,
            JsonMapper json) {
        this.distritos = distritos;
        this.zonas = zonas;
        this.auditoria = auditoria;
        this.usuario = usuario;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<DistritoCatalogoVista> catalogo(String nombre, EstadoDistrito estado) {
        return distritos.catalogo(nombre, estado).stream().map(this::aVista).toList();
    }

    @Transactional(readOnly = true)
    public DistritoCatalogoVista obtener(UUID id) {
        return aVista(buscar(id));
    }

    @Transactional
    public DistritoCatalogoVista crear(SolicitudDistrito solicitud) {
        zonas.bloquearConfiguracion();
        SolicitudDistrito datos = validar(solicitud);
        validarUnicidad(datos, null);
        Distrito distrito = new Distrito();
        distrito.setId(UUID.randomUUID());
        aplicar(distrito, datos);
        distrito = distritos.guardar(distrito);
        DistritoCatalogoVista vista = aVista(distrito);
        registrar(vista, null, AccionAuditoria.CREAR);
        return vista;
    }

    @Transactional
    public DistritoCatalogoVista editar(UUID id, SolicitudDistrito solicitud) {
        zonas.bloquearConfiguracion();
        Distrito distrito = buscar(id);
        validarVersion(distrito, solicitud.versionDistrito());
        SolicitudDistrito datos = validar(solicitud);
        DistritoCatalogoVista anterior = aVista(distrito);
        List<ErrorDeCampo> protegidos = new ArrayList<>();
        if (!anterior.identidadEditable()) {
            if (!datos.nombre().equals(distrito.getNombre()))
                protegidos.add(
                        new ErrorDeCampo("nombre", "La identidad del distrito está protegida"));
            if (!datos.ubigeo().equals(distrito.getUbigeo()))
                protegidos.add(
                        new ErrorDeCampo("ubigeo", "La identidad del distrito está protegida"));
        }
        if (!protegidos.isEmpty())
            throw ErrorDeNegocio.de(
                    409,
                    "DESP_ERROR_IDENTIDAD_DISTRITO_PROTEGIDA",
                    "No se puede cambiar la identidad del catálogo base o de un distrito asociado",
                    protegidos);
        validarUnicidad(datos, id);
        if (datos.nombre().equals(distrito.getNombre())
                && datos.provincia().equals(distrito.getProvincia())
                && datos.departamento().equals(distrito.getDepartamento())
                && datos.ubigeo().equals(distrito.getUbigeo())) return anterior;
        aplicar(distrito, datos);
        DistritoCatalogoVista vista = aVista(distritos.guardar(distrito));
        registrar(vista, anterior, AccionAuditoria.MODIFICAR);
        return vista;
    }

    @Transactional
    public DistritoCatalogoVista cambiarEstado(UUID id, EstadoDistrito estado, Long version) {
        zonas.bloquearConfiguracion();
        Distrito distrito = buscar(id);
        if (version == null || version < 0)
            throw invalido("versionDistrito", "Indica una versión entera no negativa");
        if (estado == null) throw invalido("estado", "El estado es obligatorio");
        if (distrito.getEstado() == estado) return aVista(distrito);
        validarVersion(distrito, version);
        DistritoCatalogoVista anterior = aVista(distrito);
        distrito.setEstado(estado);
        DistritoCatalogoVista vista = aVista(distritos.guardar(distrito));
        registrar(vista, anterior, AccionAuditoria.CAMBIAR_ESTADO);
        return vista;
    }

    private Distrito buscar(UUID id) {
        return distritos
                .buscarPorId(id)
                .orElseThrow(
                        () ->
                                ErrorDeNegocio.de(
                                        404,
                                        "DESP_ERROR_DISTRITO_NO_ENCONTRADO",
                                        "El distrito no existe"));
    }

    private void validarVersion(Distrito distrito, Long version) {
        if (version == null || version < 0)
            throw invalido("versionDistrito", "Indica una versión entera no negativa");
        if (!Objects.equals(version, distrito.getVersion()))
            throw ErrorDeNegocio.de(
                            409,
                            "DESP_ERROR_DISTRITO_DESACTUALIZADO",
                            "El distrito fue modificado; consulta y revisa los datos actuales")
                    .conEstadoActual(distrito.getEstado().name())
                    .conVersionDistrito(distrito.getVersion());
    }

    private SolicitudDistrito validar(SolicitudDistrito datos) {
        String nombre = limpiar(datos.nombre());
        String provincia = limpiar(datos.provincia());
        String departamento = limpiar(datos.departamento());
        String ubigeo = limpiar(datos.ubigeo());
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (nombre.isEmpty() || nombre.length() > 100)
            errores.add(new ErrorDeCampo("nombre", "Ingresa entre 1 y 100 caracteres"));
        boolean lima = provincia.equalsIgnoreCase("Lima") && departamento.equalsIgnoreCase("Lima");
        boolean callao =
                provincia.equalsIgnoreCase("Callao") && departamento.equalsIgnoreCase("Callao");
        if (!lima && !callao) {
            errores.add(
                    new ErrorDeCampo(
                            "provincia",
                            "Selecciona Lima o Callao con su departamento correspondiente"));
            errores.add(
                    new ErrorDeCampo(
                            "departamento", "Debe corresponder a la provincia Lima o Callao"));
        }
        if (!ubigeo.matches("[0-9]{6}")
                || ubigeo.endsWith("00")
                || (lima && !ubigeo.startsWith("1501"))
                || (callao && !ubigeo.startsWith("0701")))
            errores.add(
                    new ErrorDeCampo(
                            "ubigeo",
                            "Ingresa seis dígitos del ámbito elegido: 1501 o 0701 y distrito de 01 a 99"));
        if (!errores.isEmpty())
            throw ErrorDeNegocio.de(
                    400,
                    "DESP_ERROR_DATOS_DISTRITO_INVALIDOS",
                    "Revisa los datos del distrito",
                    errores);
        return new SolicitudDistrito(
                nombre,
                lima ? "Lima" : "Callao",
                lima ? "Lima" : "Callao",
                ubigeo,
                datos.versionDistrito());
    }

    private void validarUnicidad(SolicitudDistrito datos, UUID id) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (distritos.existeNombre(datos.nombre(), id))
            errores.add(
                    new ErrorDeCampo(
                            "nombre", "El nombre ya está en uso, incluso entre inactivos"));
        if (distritos.existeUbigeo(datos.ubigeo(), id))
            errores.add(
                    new ErrorDeCampo(
                            "ubigeo", "El ubigeo ya está en uso, incluso entre inactivos"));
        if (!errores.isEmpty())
            throw ErrorDeNegocio.de(
                    409,
                    "DESP_ERROR_DISTRITO_DUPLICADO",
                    "Ya existe un distrito con esos datos",
                    errores);
    }

    private void aplicar(Distrito distrito, SolicitudDistrito datos) {
        distrito.setNombre(datos.nombre());
        distrito.setProvincia(datos.provincia());
        distrito.setDepartamento(datos.departamento());
        distrito.setUbigeo(datos.ubigeo());
    }

    private DistritoCatalogoVista aVista(Distrito distrito) {
        return new DistritoCatalogoVista(
                distrito.getId(),
                distrito.getNombre(),
                distrito.getProvincia(),
                distrito.getDepartamento(),
                distrito.getUbigeo(),
                distrito.getEstado(),
                distrito.getVersion(),
                distrito.isCatalogoBase(),
                !distrito.isCatalogoBase() && !distritos.tieneAsociaciones(distrito.getId()),
                distrito.getCreadoEn(),
                distrito.getActualizadoEn());
    }

    private void registrar(
            DistritoCatalogoVista nuevo, DistritoCatalogoVista anterior, AccionAuditoria accion) {
        auditoria.registrar(
                "distrito",
                nuevo.id().toString(),
                accion,
                anterior == null ? null : json.writeValueAsString(anterior),
                json.writeValueAsString(nuevo),
                usuario.identificador());
    }

    private static String limpiar(String valor) {
        return valor == null ? "" : valor.trim();
    }

    public static ErrorDeNegocio invalido(String campo, String motivo) {
        return ErrorDeNegocio.de(
                400,
                "DESP_ERROR_DATOS_DISTRITO_INVALIDOS",
                "Revisa los datos del distrito",
                List.of(new ErrorDeCampo(campo, motivo)));
    }
}
