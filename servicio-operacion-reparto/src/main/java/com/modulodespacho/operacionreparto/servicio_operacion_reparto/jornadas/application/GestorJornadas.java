package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

@Service
@Profile("!integracion-local")
@Transactional(timeout = 15)
public class GestorJornadas {
    private final IRepositorioJornadas repositorio;
    private final Clock reloj;
    private final JsonMapper json;
    public GestorJornadas(IRepositorioJornadas repositorio, Clock reloj, JsonMapper json) { this.repositorio = repositorio; this.reloj = reloj; this.json = json; }
    public LocalDate obtenerFechaActual() { return LocalDate.now(reloj.withZone(ZoneId.of("America/Bogota"))); }

    public Jornada abrir(Apertura datos, UUID actor) {
        if (!obtenerFechaActual().equals(datos.fecha())) throw ErrorJornada.noApto("La jornada debe abrirse para hoy en America/Bogota.");
        repositorio.bloquearOperacion();
        Perfil perfil = repositorio.obtenerPerfil(datos.idRepartidor());
        if (!perfil.habilitado()) throw ErrorJornada.noApto("El repartidor debe estar activo y vinculado.");
        Vehiculo vehiculo = repositorio.obtenerVehiculo(datos.idFurgoneta());
        if (!"DISPONIBLE".equals(vehiculo.estado()) || repositorio.tieneRecursoOcupado(perfil.id(), vehiculo.id()))
            throw ErrorJornada.conflicto("Existe otra jornada activa o carga pendiente de los recursos.");
        UUID id = repositorio.crear(datos, vehiculo.limites(), actor, reloj.instant());
        repositorio.auditar(id, "APERTURA_JORNADA", actor, null);
        return repositorio.obtenerJornada(id).vista();
    }
    public Jornada cerrar(UUID id, UUID actor, String tipo) {
        repositorio.bloquearOperacion();
        return cerrarBloqueada(repositorio.obtenerJornada(id), actor, tipo);
    }
    private Jornada cerrarBloqueada(JornadaBase jornada, UUID actor, String tipo) {
        if (!jornada.activa()) return jornada.vista();
        if (jornada.pendientes()) throw ErrorJornada.conflicto("Resuelve primero los despachos asignados o en camino.");
        String anterior = repositorio.fotografiar(jornada.id());
        repositorio.cerrar(jornada.id(), tipo, reloj.instant());
        repositorio.auditar(jornada.id(), "CIERRE_JORNADA", actor, anterior);
        return repositorio.obtenerJornada(jornada.id()).vista();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Monitoreo consultarMonitoreo(LocalDate fecha, int pagina, int tamano, String estado, UUID zona) {
        if (pagina < 1 || tamano < 1 || tamano > 100 || estado != null && !Set.of("DISPONIBLE","EN_RUTA","SATURADO","FUERA_DE_TURNO").contains(estado))
            throw new ErrorJornada(400,"DESP_ERROR_CAMPOS_INVALIDOS","Revisa la paginación y el estado operativo.");
        var base = repositorio.consultarMonitoreo(fecha == null ? obtenerFechaActual() : fecha).stream()
                .filter(fila -> zona == null || fila.jornada() != null && zona.equals(fila.jornada().zona())).toList();
        Resumen resumen = new Resumen(contar(base,"DISPONIBLE"),contar(base,"EN_RUTA"),contar(base,"SATURADO"),contar(base,"FUERA_DE_TURNO"));
        var filtradas = base.stream().filter(fila -> estado == null || estado.equals(fila.estadoOperativo())).toList();
        return new Monitoreo(filtradas.stream().skip((long)(pagina - 1) * tamano).limit(tamano).map(FilaBase::vista).toList(),
                pagina,tamano,filtradas.size(),(int)Math.ceil((double)filtradas.size()/tamano),resumen,reloj.instant());
    }
    private static long contar(List<FilaBase> filas,String estado) { return filas.stream().filter(fila -> estado.equals(fila.estadoOperativo())).count(); }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Candidato> consultarDisponibles(LocalDate fecha, UUID zona) {
        LocalDate dia = fecha == null ? obtenerFechaActual() : fecha;
        return repositorio.consultarMonitoreo(dia).stream().filter(fila -> fila.disponible(dia))
                .filter(fila -> zona == null || zona.equals(fila.jornada().zona())).map(fila -> {
                    Jornada j = fila.jornada().vista();
                    return new Candidato(fila.perfil().id(),j.idJornada(),fila.perfil().nombre(),j.idZona(),j.idFurgoneta(),j.placa(),
                            fila.estadoOperativo(),j.limites(),j.ocupacion(),j.capacidadRemanente(),j.porcentajeOcupacion());
                }).toList();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detalle consultarDetalle(UUID id) { return Detalle.de(repositorio.obtenerJornada(id).vista(),repositorio.listarDespachos(id)); }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Contexto consultarContexto(UUID usuario) {
        Perfil perfil = perfilPropio(usuario);
        var jornada = repositorio.consultarMonitoreo(obtenerFechaActual()).stream().filter(fila -> fila.perfil().id().equals(perfil.id()))
                .map(FilaBase::jornada).filter(java.util.Objects::nonNull).filter(JornadaBase::activa).findFirst();
        return new Contexto(perfil.id(),jornada.map(JornadaBase::estadoOperativo).orElse("FUERA_DE_TURNO"),jornada.map(JornadaBase::vista).orElse(null));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RutaRepartidor consultarRuta(UUID usuario) {
        Perfil perfil = perfilPropio(usuario);
        LocalDate hoy = obtenerFechaActual();
        JornadaBase jornada = repositorio.buscarJornadaDelDia(perfil.id(),hoy)
                .filter(JornadaBase::activa).orElse(null);
        if (jornada == null) return new RutaRepartidor(null,hoy,"FUERA_DE_TURNO",List.of());
        return new RutaRepartidor(jornada.id(),jornada.fecha(),jornada.estadoOperativo(),
                repositorio.listarProyecciones(jornada.id()).stream().map(GestorJornadas::despachoRuta).toList());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DetalleDespacho consultarDespachoPropio(UUID usuario, UUID idDespacho) {
        Perfil perfil = perfilPropio(usuario);
        JornadaBase jornada = repositorio.buscarJornadaDelDia(perfil.id(),obtenerFechaActual())
                .filter(JornadaBase::activa)
                .orElseThrow(() -> new ErrorJornada(404,"DESP_ERROR_DESPACHO_NO_ENCONTRADO","El despacho no existe."));
        Proyeccion despacho = repositorio.buscarProyeccion(jornada.id(),idDespacho)
                .orElseThrow(() -> new ErrorJornada(404,"DESP_ERROR_DESPACHO_NO_ENCONTRADO","El despacho no existe."));
        return despachoDetalle(jornada.id(),despacho);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResumenJornada consultarResumenPropio(UUID usuario) {
        Perfil perfil = perfilPropio(usuario);
        LocalDate hoy = obtenerFechaActual();
        JornadaBase jornada = repositorio.buscarJornadaDelDia(perfil.id(),hoy).orElse(null);
        if (jornada == null) return new ResumenJornada(null,hoy,null,null,0,0,0,0,0,List.of());
        var despachos = repositorio.listarProyecciones(jornada.id());
        // Contrato ampliado: los fallidos, con o sin intento, regresan físicamente al centro.
        var devolver = despachos.stream().filter(d -> "FALLIDO".equals(d.estado()) || "NO_INTENTADO".equals(d.estado()))
                .map(d -> new PaqueteDevolucion(d.idDespacho(),d.codigoRastreoInterno(),
                        d.destino() == null ? null : d.destino().direccion(),d.destino() == null ? null : d.destino().distrito(),
                        d.motivoFallo() == null && "NO_INTENTADO".equals(d.estado()) ? "NO_INTENTADO" : d.motivoFallo())).toList();
        return new ResumenJornada(jornada.id(),jornada.fecha(),jornada.estado(),jornada.cerradaEn(),despachos.size(),
                despachos.stream().filter(d -> "ENTREGADO".equals(d.estado())).count(),
                despachos.stream().filter(Proyeccion::fallidoConIntento).count(),
                despachos.stream().filter(Proyeccion::noIntentado).count(),
                despachos.stream().filter(d -> "ASIGNADO".equals(d.estado()) || "EN_CAMINO".equals(d.estado())).count(),devolver);
    }
    /** Lectura previa a un comando de campo; los datos se vuelven a validar en Gestión bajo su bloqueo. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DespachoEnRuta despachoEnRuta(UUID usuario, UUID idDespacho) {
        Perfil perfil = perfilPropio(usuario);
        JornadaBase jornada = repositorio.buscarJornadaDelDia(perfil.id(),obtenerFechaActual()).filter(JornadaBase::activa)
                .orElseThrow(() -> new ErrorJornada(409,"DESP_ERROR_FUERA_DE_TURNO","No tienes una jornada activa."));
        Proyeccion despacho = repositorio.buscarProyeccion(jornada.id(),idDespacho)
                .orElseThrow(() -> new ErrorJornada(404,"DESP_ERROR_DESPACHO_NO_ENCONTRADO","El despacho no existe."));
        return new DespachoEnRuta(perfil.id(),jornada.id(),despacho);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DespachoEnRuta> pendientesPropios(UUID usuario, UUID idJornada) {
        Perfil perfil = perfilPropio(usuario);
        JornadaBase jornada = repositorio.obtenerJornada(idJornada);
        if (!jornada.repartidor().equals(perfil.id())) throw new ErrorJornada(403,"DESP_ERROR_SCOPE_INSUFICIENTE","La jornada no pertenece a tu perfil.");
        if (!jornada.activa()) return List.of();
        return repositorio.listarProyecciones(jornada.id()).stream()
                .filter(d -> "ASIGNADO".equals(d.estado()) || "EN_CAMINO".equals(d.estado()))
                .map(d -> new DespachoEnRuta(perfil.id(),jornada.id(),d)).toList();
    }
    private static DespachoRuta despachoRuta(Proyeccion despacho) {
        boolean mostrarDatosPersonales = datosPersonalesVisibles(despacho.estado());
        validarDatosPersonales(despacho,mostrarDatosPersonales);
        return new DespachoRuta(despacho.idDespacho(),despacho.codigoRastreoInterno(),despacho.estado(),
                despacho.secuenciaRuta(),despacho.fechaProgramada(),despacho.numeroIntentos(),despacho.maximoIntentos(),
                mostrarDatosPersonales ? despacho.destinatario().nombre() : null,
                mostrarDatosPersonales ? despacho.destino().direccion() : null,
                mostrarDatosPersonales ? despacho.destino().distrito() : null,despacho.motivoFallo(),despacho.hitos());
    }
    private static DetalleDespacho despachoDetalle(UUID jornada,Proyeccion despacho) {
        boolean mostrarDatosPersonales = datosPersonalesVisibles(despacho.estado());
        validarDatosPersonales(despacho,mostrarDatosPersonales);
        return new DetalleDespacho(jornada,despacho.idDespacho(),despacho.codigoRastreoInterno(),despacho.estado(),
                despacho.secuenciaRuta(),despacho.fechaProgramada(),despacho.numeroIntentos(),despacho.maximoIntentos(),
                mostrarDatosPersonales ? despacho.destinatario() : null,
                mostrarDatosPersonales ? despacho.destino() : null,despacho.motivoFallo(),despacho.hitos());
    }
    private static boolean datosPersonalesVisibles(String estado) {
        return "ASIGNADO".equals(estado) || "EN_CAMINO".equals(estado);
    }
    private static void validarDatosPersonales(Proyeccion despacho,boolean mostrarDatosPersonales) {
        if (mostrarDatosPersonales && (despacho.destinatario() == null || despacho.destino() == null
                || despacho.destinatario().nombre() == null || despacho.destino().direccion() == null
                || despacho.destino().distrito() == null)) throw ErrorJornada.noDisponible();
    }
    public Jornada cerrarPropia(UUID usuario, UUID idJornada) {
        repositorio.bloquearOperacion();
        Perfil perfil = perfilPropio(usuario);
        JornadaBase jornada = repositorio.obtenerJornada(idJornada);
        if (!jornada.repartidor().equals(perfil.id())) throw new ErrorJornada(403,"DESP_ERROR_SCOPE_INSUFICIENTE","La jornada no pertenece a tu perfil.");
        return cerrarBloqueada(jornada,usuario,"REPARTIDOR");
    }
    private Perfil perfilPropio(UUID usuario) {
        return repositorio.buscarPerfilDeUsuario(usuario).filter(Perfil::habilitado)
                .orElseThrow(() -> new ErrorJornada(403,"DESP_ERROR_SCOPE_INSUFICIENTE","No tienes un perfil habilitado."));
    }
    public ConfirmacionReserva reservar(UUID clave, SolicitudReserva datos, String actor) {
        if (clave.version() != 4) throw new ErrorJornada(400,"DESP_ERROR_CAMPOS_INVALIDOS","La clave de asignación debe ser UUID v4.");
        repositorio.bloquearOperacion();
        var previa = repositorio.buscarReservaPorClave(clave);
        if (previa.isPresent()) {
            Reserva r = previa.get();
            if (!r.despacho().equals(datos.idDespacho()) || !r.jornada().equals(datos.idJornada()) || !r.repartidor().equals(datos.idRepartidor()) || !r.carga().mismaCarga(datos.requerido()))
                throw new ErrorJornada(409,"DESP_ERROR_CLAVE_REUTILIZADA","La clave corresponde a otro contenido.");
            if (r.respuesta() == null) throw ErrorJornada.noDisponible();
            return json.readValue(r.respuesta(),ConfirmacionReserva.class);
        }
        if (repositorio.tieneReservaActiva(datos.idDespacho())) throw new ErrorJornada(409,"DESP_ERROR_RESERVA_ANTERIOR_ACTIVA","La reserva anterior del despacho sigue activa.");
        JornadaBase j = repositorio.obtenerJornada(datos.idJornada());
        Perfil perfil = repositorio.obtenerPerfil(datos.idRepartidor());
        if (!j.activa() || !j.fecha().equals(obtenerFechaActual()) || !j.repartidor().equals(perfil.id()) || !perfil.habilitado()
                || !"DISPONIBLE".equals(repositorio.obtenerVehiculo(j.furgoneta()).estado()))
            throw new ErrorJornada(409,"DESP_ERROR_REPARTIDOR_NO_HABILITADO","La jornada no está habilitada para reservar.");
        Capacidad remanente = j.limites().menos(j.ocupacion());
        if (!datos.requerido().positiva() || !remanente.admite(datos.requerido())) {
            var campos = new java.util.ArrayList<String>();
            if (datos.requerido().pesoKg().compareTo(remanente.pesoKg())>0) campos.add("pesoKg");
            if (datos.requerido().volumenM3().compareTo(remanente.volumenM3())>0) campos.add("volumenM3");
            if (datos.requerido().paquetes()>remanente.paquetes()) campos.add("paquetes");
            throw new ErrorJornada(422,"DESP_ERROR_CAPACIDAD_EXCEDIDA","La carga supera la capacidad remanente.").conCampos(campos);
        }
        var respuesta = new ConfirmacionReserva(UUID.randomUUID(),"CONFIRMADA",remanente.menos(datos.requerido()));
        repositorio.guardarReserva(respuesta.idReserva(),clave,datos,json.writeValueAsString(respuesta),actor,reloj.instant());
        return respuesta;
    }
    public Liberacion liberar(UUID id, UUID despacho, String motivo, String actor) {
        repositorio.bloquearOperacion();
        Reserva reserva = repositorio.obtenerReserva(id);
        if (!reserva.despacho().equals(despacho)) throw new ErrorJornada(409,"DESP_ERROR_RESERVA_NO_COINCIDE","La reserva no pertenece al despacho comunicado.");
        if ("ACTIVA".equals(reserva.estado())) repositorio.liberar(id,motivo,actor,reloj.instant());
        Reserva resultado = repositorio.obtenerReserva(id);
        if (resultado.liberadaEn() == null || resultado.motivo() == null) throw ErrorJornada.noDisponible();
        return new Liberacion(id,despacho,"LIBERADA",resultado.motivo(),resultado.liberadaEn());
    }
    public void proyectar(UUID id, Proyeccion datos, String actor) {
        if (!id.equals(datos.idDespacho())) throw new ErrorJornada(400,"DESP_ERROR_CAMPOS_INVALIDOS","El identificador de la ruta no coincide.");
        repositorio.bloquearOperacion();
        if (repositorio.buscarVersionProyeccion(id).filter(version -> version >= datos.version()).isPresent()) return;
        Reserva reserva = repositorio.obtenerReserva(datos.idReserva());
        if (!reserva.despacho().equals(id) || !reserva.jornada().equals(datos.idJornada()))
            throw new ErrorJornada(409,"DESP_ERROR_PROYECCION_INCOMPATIBLE","La reserva y jornada no corresponden al despacho.");
        if (Set.of("ASIGNADO","EN_CAMINO").contains(datos.estado()) && (!"ACTIVA".equals(reserva.estado()) || !repositorio.obtenerJornada(datos.idJornada()).activa()))
            throw new ErrorJornada(409,"DESP_ERROR_PROYECCION_INCOMPATIBLE","La jornada o reserva ya no admite carga.");
        repositorio.guardarProyeccion(datos,actor);
    }
}
