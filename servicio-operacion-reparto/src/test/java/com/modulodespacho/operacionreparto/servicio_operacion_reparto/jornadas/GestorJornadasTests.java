package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Puerto de persistencia simulado: las transacciones y bloqueos reales se prueban por separado. */
class GestorJornadasTests {
    private final IRepositorioJornadas repositorio=mock(IRepositorioJornadas.class);
    private final Clock reloj=Clock.fixed(Instant.parse("2026-10-18T03:30:00Z"),ZoneOffset.UTC);
    private final JsonMapper json=JsonMapper.builder().build();
    private final GestorJornadas gestor=new GestorJornadas(repositorio,reloj,json);
    private final UUID id=UUID.randomUUID(),perfil=UUID.randomUUID(),vehiculo=UUID.randomUUID(),zona=UUID.randomUUID(),actor=UUID.randomUUID();
    private final LocalDate hoy=LocalDate.of(2026,10,17);
    private final Capacidad limites=new Capacidad(new BigDecimal("500"),new BigDecimal("4"),80);
    private final Perfil habilitado=new Perfil(perfil,"Perfil Prueba","ACTIVO","VINCULADO");
    private final Vehiculo disponible=new Vehiculo(vehiculo,"TEST-01","DISPONIBLE",limites);

    @Test void abreSoloHoyYFotografiaCapacidadesDelMaestroConAuditoria() {
        when(repositorio.obtenerPerfil(perfil)).thenReturn(habilitado);when(repositorio.obtenerVehiculo(vehiculo)).thenReturn(disponible);
        when(repositorio.crear(any(),eq(limites),eq(actor),eq(reloj.instant()))).thenReturn(id);
        when(repositorio.obtenerJornada(id)).thenReturn(base("ACTIVA",false,Capacidad.cero()));
        Jornada resultado=gestor.abrir(new Apertura(perfil,vehiculo,zona,hoy),actor);
        assertThat(resultado.limites()).isEqualTo(limites);
        var orden=inOrder(repositorio);orden.verify(repositorio).bloquearOperacion();orden.verify(repositorio).obtenerPerfil(perfil);
        verify(repositorio).auditar(id,"APERTURA_JORNADA",actor,null);
    }
    @Test void unaFechaAjenaNoTomaBloqueosNiCreaUnaJornada() {
        assertThatThrownBy(() -> gestor.abrir(new Apertura(perfil,vehiculo,zona,hoy.plusDays(1)),actor)).isInstanceOf(ErrorJornada.class);
        verifyNoInteractions(repositorio);
    }
    @Test void rechazaPerfilPendienteYRecursoOcupadoSinCrear() {
        when(repositorio.obtenerPerfil(perfil)).thenReturn(new Perfil(perfil,"Prueba","ACTIVO","PENDIENTE"));
        assertThatThrownBy(() -> gestor.abrir(new Apertura(perfil,vehiculo,zona,hoy),actor)).isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(422));
        when(repositorio.obtenerPerfil(perfil)).thenReturn(habilitado);when(repositorio.obtenerVehiculo(vehiculo)).thenReturn(disponible);when(repositorio.tieneRecursoOcupado(perfil,vehiculo)).thenReturn(true);
        assertThatThrownBy(() -> gestor.abrir(new Apertura(perfil,vehiculo,zona,hoy),actor)).isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(409));
        verify(repositorio,never()).crear(any(),any(),any(),any());
    }
    @Test void elResumenCuentaAntesDeFiltrarYPaginar() {
        var disponibleFila=new FilaBase(habilitado,disponible,base("ACTIVA",false,Capacidad.cero()));
        var saturada=new FilaBase(new Perfil(UUID.randomUUID(),"Saturado","ACTIVO","VINCULADO"),disponible,base("ACTIVA",true,limites));
        when(repositorio.consultarMonitoreo(hoy)).thenReturn(List.of(disponibleFila,saturada,new FilaBase(new Perfil(UUID.randomUUID(),"Sin turno","ACTIVO","PENDIENTE"),null,null)));
        var respuesta=gestor.consultarMonitoreo(null,2,1,"DISPONIBLE",null);
        assertThat(respuesta.resumen()).isEqualTo(new Resumen(1,0,1,1));
        assertThat(respuesta.totalElementos()).isEqualTo(1);assertThat(respuesta.contenido()).isEmpty();
    }
    @Test void disponibilidadExcluyeSaturadosYVehiculosNoDisponibles() {
        when(repositorio.consultarMonitoreo(hoy)).thenReturn(List.of(new FilaBase(habilitado,disponible,base("ACTIVA",true,Capacidad.cero())),
                new FilaBase(habilitado,disponible,base("ACTIVA",false,limites)),
                new FilaBase(habilitado,new Vehiculo(vehiculo,"TEST-01","INACTIVA",limites),base("ACTIVA",false,Capacidad.cero()))));
        assertThat(gestor.consultarDisponibles(null,zona)).hasSize(1);
        assertThat(gestor.consultarDisponibles(null,zona).getFirst().estadoOperativo()).isEqualTo("EN_RUTA");
    }
    @Test void cierreConPendientesNoLiberaNiAuditaYRepetirElCierreNoCambiaSuFecha() {
        when(repositorio.obtenerJornada(id)).thenReturn(base("ACTIVA",true,Capacidad.cero()));
        assertThatThrownBy(() -> gestor.cerrar(id,actor,"GESTOR_DESPACHO")).isInstanceOf(ErrorJornada.class);
        when(repositorio.obtenerJornada(id)).thenReturn(base("CERRADA",false,Capacidad.cero()));
        assertThat(gestor.cerrar(id,actor,"GESTOR_DESPACHO").estado()).isEqualTo("CERRADA");
        verify(repositorio,never()).cerrar(any(),any(),any());verify(repositorio,never()).auditar(any(),any(),any(),any());
    }
    @Test void cierreSinPendientesAuditaUnaVezSinLiberarReservasDeRetorno() {
        when(repositorio.obtenerJornada(id)).thenReturn(base("ACTIVA",false,new Capacidad(BigDecimal.TEN,BigDecimal.ONE,1)),base("CERRADA",false,new Capacidad(BigDecimal.TEN,BigDecimal.ONE,1)));
        when(repositorio.fotografiar(id)).thenReturn("{}");
        assertThat(gestor.cerrar(id,actor,"GESTOR_DESPACHO").ocupacion().pesoKg()).isEqualByComparingTo("10");
        verify(repositorio).cerrar(id,"GESTOR_DESPACHO",reloj.instant());verify(repositorio).auditar(id,"CIERRE_JORNADA",actor,"{}");
        verify(repositorio,never()).liberar(any(),any(),any(),any());
    }
    @Test void unaReservaRepetidaInclusoLiberadaDevuelveLaConfirmacionOriginal() {
        UUID clave=UUID.randomUUID(),despacho=UUID.randomUUID(),reserva=UUID.randomUUID();
        Capacidad carga=new Capacidad(BigDecimal.TEN,BigDecimal.ONE,1);
        var original=new ConfirmacionReserva(reserva,"CONFIRMADA",limites.menos(carga));
        when(repositorio.buscarReservaPorClave(clave)).thenReturn(Optional.of(new Reserva(reserva,clave,despacho,id,perfil,carga,"LIBERADA","COMPENSACION",reloj.instant(),json.writeValueAsString(original))));
        assertThat(gestor.reservar(clave,new SolicitudReserva(despacho,perfil,id,carga),"modulo-despacho")).isEqualTo(original);
        verify(repositorio,never()).guardarReserva(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(() -> gestor.reservar(clave,new SolicitudReserva(UUID.randomUUID(),perfil,id,carga),"modulo-despacho"))
                .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_CLAVE_REUTILIZADA"));
    }
    @Test void capacidadInsuficienteInformaLasDimensionesSinSobrerreservar() {
        when(repositorio.buscarReservaPorClave(any())).thenReturn(Optional.empty());when(repositorio.obtenerJornada(id)).thenReturn(base("ACTIVA",false,Capacidad.cero()));
        when(repositorio.obtenerPerfil(perfil)).thenReturn(habilitado);when(repositorio.obtenerVehiculo(vehiculo)).thenReturn(disponible);
        var datos=new SolicitudReserva(UUID.randomUUID(),perfil,id,new Capacidad(new BigDecimal("501"),new BigDecimal("5"),81));
        assertThatThrownBy(() -> gestor.reservar(UUID.randomUUID(),datos,"modulo-despacho"))
                .isInstanceOfSatisfying(ErrorJornada.class,e -> {assertThat(e.estado()).isEqualTo(422);assertThat(e.campos()).containsExactly("pesoKg","volumenM3","paquetes");});
        verify(repositorio,never()).guardarReserva(any(),any(),any(),any(),any(),any());
    }
    @Test void reservaAnteriorActivaNoSeConfundeConUnaAsignacionNueva() {
        UUID despacho=UUID.randomUUID();when(repositorio.buscarReservaPorClave(any())).thenReturn(Optional.empty());when(repositorio.tieneReservaActiva(despacho)).thenReturn(true);
        assertThatThrownBy(() -> gestor.reservar(UUID.randomUUID(),new SolicitudReserva(despacho,perfil,id,limites),"modulo-despacho"))
                .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_RESERVA_ANTERIOR_ACTIVA"));
    }
    @Test void liberacionIdempotenteConservaPrimeraFechaYRechazaDespachoAjeno() {
        UUID despacho=UUID.randomUUID(),reserva=UUID.randomUUID();
        when(repositorio.obtenerReserva(reserva)).thenReturn(new Reserva(reserva,null,despacho,id,perfil,limites,"LIBERADA","RECEPCION_EN_CENTRO",reloj.instant(),null));
        assertThat(gestor.liberar(reserva,despacho,"COMPENSACION","modulo-despacho").motivoLiberacion()).isEqualTo("RECEPCION_EN_CENTRO");
        assertThatThrownBy(() -> gestor.liberar(reserva,UUID.randomUUID(),"COMPENSACION","modulo-despacho")).isInstanceOf(ErrorJornada.class);
        verify(repositorio,never()).liberar(any(),any(),any(),any());
    }
    @Test void transferenciaValidaCadaDimensionAntesDeTocarLaReservaOrigen() {
        UUID origen=UUID.randomUUID(),despacho=UUID.randomUUID();
        when(repositorio.obtenerJornada(id)).thenReturn(base("ACTIVA",false,Capacidad.cero()));
        when(repositorio.obtenerPerfil(perfil)).thenReturn(habilitado);
        when(repositorio.obtenerVehiculo(vehiculo)).thenReturn(disponible);
        for(Capacidad carga:List.of(new Capacidad(new BigDecimal("501"),BigDecimal.ONE,1),
                new Capacidad(BigDecimal.ONE,new BigDecimal("5"),1),new Capacidad(BigDecimal.ONE,BigDecimal.ONE,81))) {
            when(repositorio.obtenerReserva(origen)).thenReturn(new Reserva(origen,null,despacho,UUID.randomUUID(),UUID.randomUUID(),carga,"ACTIVA",null,null,null));
            assertThatThrownBy(() -> gestor.transferir(UUID.randomUUID(),new SolicitudTransferencia(origen,despacho,perfil,id),"modulo-despacho"))
                    .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(422));
        }
        verify(repositorio,never()).liberar(any(),any(),any(),any());
        verify(repositorio,never()).guardarReserva(any(),any(),any(),any(),any(),any());
    }
    @Test void proyeccionesAntiguasNoReemplazanLaVersionActualNiConsultanReferenciasViejas() {
        UUID despacho=UUID.randomUUID();when(repositorio.buscarVersionProyeccion(despacho)).thenReturn(Optional.of(3L));
        gestor.proyectar(despacho,new Proyeccion(despacho,"pedido","TRK","ASIGNADO",id,UUID.randomUUID(),1,null,null,hoy,0,2,reloj.instant(),2,null,null),"modulo-despacho");
        verify(repositorio,never()).guardarProyeccion(any(),any());verify(repositorio,never()).obtenerReserva(any());
    }
    @Test void proyeccionNuevaRechazaReservaDeOtroDespachoSinEscrituras() {
        UUID despacho=UUID.randomUUID(),reserva=UUID.randomUUID();
        when(repositorio.buscarVersionProyeccion(despacho)).thenReturn(Optional.empty());
        when(repositorio.obtenerReserva(reserva)).thenReturn(new Reserva(reserva,null,UUID.randomUUID(),id,perfil,limites,"ACTIVA",null,null,null));
        var datos=new Proyeccion(despacho,"pedido","TRK","ASIGNADO",id,reserva,1,null,null,hoy,0,1,reloj.instant(),2,null,null);
        assertThatThrownBy(() -> gestor.proyectar(despacho,datos,"modulo-despacho"))
                .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.codigo()).isEqualTo("DESP_ERROR_PROYECCION_INCOMPATIBLE"));
        verify(repositorio,never()).guardarProyeccion(any(),any());
    }
    @Test void contextoPropioNoEntregaJornadasDeOtraPersona() {
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.consultarMonitoreo(hoy)).thenReturn(List.of(new FilaBase(new Perfil(UUID.randomUUID(),"Otro","ACTIVO","VINCULADO"),disponible,base("ACTIVA",false,Capacidad.cero()))));
        Contexto contexto=gestor.consultarContexto(actor);assertThat(contexto.idRepartidor()).isEqualTo(perfil);assertThat(contexto.jornada()).isNull();
    }
    @Test void rutaPropiaSoloEntregaCamposOperativosMinimosDeLaJornadaActiva() {
        UUID despacho=UUID.randomUUID();
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.buscarJornadaDelDia(perfil,hoy)).thenReturn(Optional.of(base("ACTIVA",false,Capacidad.cero())));
        when(repositorio.listarProyecciones(id)).thenReturn(List.of(proyeccion(despacho,"ASIGNADO",0)));

        RutaRepartidor ruta=gestor.consultarRuta(actor);

        assertThat(ruta.idJornada()).isEqualTo(id);
        assertThat(ruta.despachos()).singleElement().satisfies(fila -> {
            assertThat(fila.idDespacho()).isEqualTo(despacho);
            assertThat(fila.maximoIntentos()).isEqualTo(2);
            assertThat(fila.direccion()).isEqualTo("Dirección de prueba");
        });
        verify(repositorio).listarProyecciones(id);
    }
    @Test void detallePropioNoPermiteConsultarDespachosAjenoNiDeJornadaCerrada() {
        UUID despacho=UUID.randomUUID();
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.buscarJornadaDelDia(perfil,hoy)).thenReturn(Optional.of(base("CERRADA",false,Capacidad.cero())));
        assertThatThrownBy(() -> gestor.consultarDespachoPropio(actor,despacho))
                .isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(404));
        verify(repositorio,never()).buscarProyeccion(any(),any());
    }
    @Test void detalleDeDespachoResueltoOmiteDatosPersonales() {
        UUID despacho=UUID.randomUUID();
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.buscarJornadaDelDia(perfil,hoy)).thenReturn(Optional.of(base("ACTIVA",false,Capacidad.cero())));
        when(repositorio.buscarProyeccion(id,despacho)).thenReturn(Optional.of(proyeccion(despacho,"ENTREGADO",1)));

        DetalleDespacho detalle=gestor.consultarDespachoPropio(actor,despacho);

        assertThat(detalle.idDespacho()).isEqualTo(despacho);
        assertThat(detalle.destinatario()).isNull();
        assertThat(detalle.destino()).isNull();
    }
    @Test void resumenPropioClasificaIntentosFallidosYNoIntentadosSinExponerDatosPersonales() {
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.buscarJornadaDelDia(perfil,hoy)).thenReturn(Optional.of(base("CERRADA",false,Capacidad.cero())));
        when(repositorio.listarProyecciones(id)).thenReturn(List.of(proyeccion(UUID.randomUUID(),"ENTREGADO",1),
                proyeccion(UUID.randomUUID(),"FALLIDO",1),proyeccion(UUID.randomUUID(),"NO_INTENTADO",0),
                proyeccion(UUID.randomUUID(),"EN_CAMINO",0)));

        ResumenJornada resumen=gestor.consultarResumenPropio(actor);

        assertThat(resumen.asignados()).isEqualTo(4);
        assertThat(resumen.entregados()).isEqualTo(1);
        assertThat(resumen.fallidosConIntento()).isEqualTo(1);
        assertThat(resumen.noIntentados()).isEqualTo(1);
        assertThat(resumen.pendientes()).isEqualTo(1);
        assertThat(resumen.estadoJornada()).isEqualTo("CERRADA");
        assertThat(resumen.cerradaEn()).isEqualTo(reloj.instant());
        assertThat(resumen.paquetesPorDevolver()).hasSize(2)
                .allSatisfy(paquete -> assertThat(paquete.direccion()).isEqualTo("Dirección de prueba"));
    }
    @Test void elCierrePropioConservaLaJornadaSolicitadaYNoPuedeCerrarUnaAjena() {
        when(repositorio.buscarPerfilDeUsuario(actor)).thenReturn(Optional.of(habilitado));
        when(repositorio.obtenerJornada(id)).thenReturn(base("CERRADA",false,Capacidad.cero()));
        assertThat(gestor.cerrarPropia(actor,id).idJornada()).isEqualTo(id);
        JornadaBase ajena=new JornadaBase(id,UUID.randomUUID(),vehiculo,"TEST-01",zona,hoy,"ACTIVA",null,limites,Capacidad.cero(),false,false);
        when(repositorio.obtenerJornada(id)).thenReturn(ajena);
        assertThatThrownBy(() -> gestor.cerrarPropia(actor,id)).isInstanceOfSatisfying(ErrorJornada.class,e -> assertThat(e.estado()).isEqualTo(403));
        verify(repositorio,never()).cerrar(any(),any(),any());
    }
    private JornadaBase base(String estado,boolean enCamino,Capacidad carga) { return new JornadaBase(id,perfil,vehiculo,"TEST-01",zona,hoy,estado,
            "CERRADA".equals(estado)?reloj.instant():null,limites,carga,enCamino,enCamino); }
    private Proyeccion proyeccion(UUID despacho,String estado,int intentos) {
        return new Proyeccion(despacho,"pedido","TRK",estado,id,UUID.randomUUID(),1,
                new Destinatario("Persona Prueba","999111222"),
                new Destino("Dirección de prueba",null,null,null,"Prueba",null,null),hoy,intentos,1,reloj.instant(),2,null,null);
    }
}
