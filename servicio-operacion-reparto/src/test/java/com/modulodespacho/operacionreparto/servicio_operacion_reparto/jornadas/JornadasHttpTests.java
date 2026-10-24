package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas;



import java.math.BigDecimal;

import java.time.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

import org.springframework.context.annotation.Import;

import org.springframework.security.oauth2.jwt.*;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.api.*;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad.*;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.errores.RespuestasSeguridad;

import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.web.FiltroCorrelacion;

import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



/** Seguridad y transporte reales de Spring; persistencia, emisor JWT y zonas son dobles. */

@WebMvcTest(controllers={ControladorJornadas.class,ControladorJornadaPropia.class,ControladorCapacidadInterna.class},properties="SEGURIDAD_URL=http://127.0.0.1:1")

@Import({ConfiguracionSeguridad.class,RespuestasSeguridad.class,FiltroCorrelacion.class,ErroresJornadas.class})

class JornadasHttpTests {

    @Autowired MockMvc http;

    @MockitoBean JwtDecoder decodificador;

    @MockitoBean GestorJornadas gestor;
    @MockitoBean com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.OperacionCampo campo;

    @MockitoBean ConsultaZonaHttp zonas;

    @MockitoBean ValidadorPerfilRepartidor perfiles;

    private final UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),repartidor=UUID.randomUUID(),vehiculo=UUID.randomUUID(),zona=UUID.randomUUID();

    private final LocalDate hoy=LocalDate.of(2026,10,17);

    @BeforeEach void preparar() {

        when(decodificador.decode("gestor")).thenReturn(token("acceso",actor.toString(),List.of("GESTOR_DESPACHO"),""));

        when(decodificador.decode("otro")).thenReturn(token("acceso",actor.toString(),List.of(),""));

        when(decodificador.decode("servicio")).thenReturn(token("servicio","modulo-despacho",List.of(),"operacion:capacidad:reservar operacion:capacidad:liberar operacion:proyecciones:escribir"));

        when(decodificador.decode("ajeno")).thenReturn(token("servicio","modulo-ajeno",List.of(),"operacion:capacidad:reservar"));

        when(decodificador.decode("sin-scope")).thenReturn(token("servicio","modulo-despacho",List.of(),""));

        when(gestor.obtenerFechaActual()).thenReturn(hoy);

    }

    @Test void exigeGestorAntesDeConsultarMonitoreoYDisponibilidad() throws Exception {

        for(String ruta:List.of("/api/v1/flota/monitoreo","/api/v1/repartidores/disponibles","/api/v1/jornadas/"+id)) {

            http.perform(get(ruta)).andExpect(status().isUnauthorized());

            http.perform(get(ruta).header("Authorization","Bearer otro")).andExpect(status().isForbidden());

            http.perform(get(ruta).header("Authorization","Bearer servicio")).andExpect(status().isForbidden());

        }

        verifyNoInteractions(zonas);verify(gestor,never()).consultarMonitoreo(any(),anyInt(),anyInt(),any(),any());

    }

    @Test void unaAperturaValidaVerificaZonaYDevuelveLaJornadaConfirmada() throws Exception {

        when(gestor.abrir(any(),eq(actor))).thenReturn(jornada("ACTIVA"));

        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura()))

                .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/jornadas/"+id))

                .andExpect(jsonPath("$.idJornada").value(id.toString())).andExpect(jsonPath("$.limites.pesoKg").value(500));

        var orden=inOrder(zonas,gestor);orden.verify(gestor).obtenerFechaActual();orden.verify(zonas).verificar(zona,"prueba");orden.verify(gestor).abrir(any(),eq(actor));

    }

    @Test void formatoInvalidoOZonaNoAptaNoEjecutanApertura() throws Exception {

        for(String cuerpo:List.of("{}",apertura().replace("\"fecha\":","\"ajeno\":true,\"fecha\":"))) {

            http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(cuerpo)).andExpect(status().isBadRequest());

        }

        doThrow(ErrorJornada.noApto("Zona inactiva")).when(zonas).verificar(zona,"prueba");

        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isUnprocessableEntity());

        verify(gestor,never()).abrir(any(),any());

    }

    @Test void validaConsultaYCierreSinAceptarCamposAjenos() throws Exception {

        http.perform(get("/api/v1/flota/monitoreo?pagina=0").header("Authorization","Bearer gestor")).andExpect(status().isBadRequest());

        http.perform(post("/api/v1/jornadas/"+id+"/cierre").header("Authorization","Bearer gestor").contentType("application/json").content("{}"))

                .andExpect(status().isBadRequest());

        when(gestor.cerrar(id,actor,"GESTOR_DESPACHO")).thenThrow(ErrorJornada.conflicto("Hay pendientes"));

        http.perform(post("/api/v1/jornadas/"+id+"/cierre").header("Authorization","Bearer gestor"))

                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_CONFLICTO_JORNADA"));

    }

    @Test void rutasInternasExigenIdentidadTecnicaYScopeExactos() throws Exception {

        String ruta="/internal/v1/capacidad/reservas",cuerpo=reserva();

        for(String credencial:List.of("gestor","otro","sin-scope","ajeno")) {

            http.perform(post(ruta).header("Authorization","Bearer "+credencial).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(cuerpo)).andExpect(status().isForbidden());

        }

        when(decodificador.decode("solo-reservar")).thenReturn(token("servicio","modulo-despacho",List.of(),"operacion:capacidad:reservar"));
        for(String protegida:List.of("/internal/v1/capacidad/reservas/"+id+"/liberacion","/internal/v1/proyecciones/despachos/"+id)) {

            // Un token con permiso de reserva no concede liberación ni escritura de proyecciones.

            http.perform(post(protegida).header("Authorization","Bearer solo-reservar").contentType("application/json").content("{}"))

                    .andExpect(status().isForbidden());

        }

        UUID clave=UUID.randomUUID();when(gestor.reservar(eq(clave),any(),eq("modulo-despacho"))).thenReturn(new ConfirmacionReserva(id,"CONFIRMADA",new Capacidad(BigDecimal.ONE,BigDecimal.ONE,1)));

        http.perform(post(ruta).header("Authorization","Bearer servicio").header("Idempotency-Key",clave).contentType("application/json").content(cuerpo))

                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADA"));

        http.perform(post(ruta).header("Authorization","Bearer servicio").contentType("application/json").content(cuerpo)).andExpect(status().isBadRequest());

    }

    @Test void excesoDeCapacidadDevuelveLasDimensionesQueConsumeF02() throws Exception {

        when(gestor.reservar(any(),any(),any())).thenThrow(new ErrorJornada(422,"DESP_ERROR_CAPACIDAD_EXCEDIDA","Excede").conCampos(List.of("pesoKg","paquetes")));

        http.perform(post("/internal/v1/capacidad/reservas").header("Authorization","Bearer servicio").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(reserva()))

                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errores[0].campo").value("pesoKg"));

    }

    @Test void contextoDeCampoUsaElPerfilDelTokenSinAceptarIdentificadorAjeno() throws Exception {

        when(perfiles.permite(any())).thenReturn(true);

        when(gestor.consultarContexto(actor)).thenReturn(new Contexto(repartidor,"FUERA_DE_TURNO",null));

        http.perform(get("/api/v1/repartidor/jornada").header("Authorization","Bearer otro"))

                .andExpect(status().isOk()).andExpect(jsonPath("$.idRepartidor").value(repartidor.toString()));

        verify(gestor).consultarContexto(actor);

    }

    private String apertura() { return "{\"idRepartidor\":\""+repartidor+"\",\"idFurgoneta\":\""+vehiculo+"\",\"idZona\":\""+zona+"\",\"fecha\":\""+hoy+"\"}"; }

    private String reserva() { return "{\"idDespacho\":\""+UUID.randomUUID()+"\",\"idRepartidor\":\""+repartidor+"\",\"idJornada\":\""+id+"\",\"requerido\":{\"pesoKg\":1,\"volumenM3\":1,\"paquetes\":1}}"; }

    private Jornada jornada(String estado) { Capacidad capacidad=new Capacidad(new BigDecimal("500"),new BigDecimal("4"),80);return new Jornada(id,repartidor,vehiculo,"TEST-01",zona,hoy,estado,null,capacidad,Capacidad.cero(),capacidad,new Porcentajes(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)); }

    private Jwt token(String tipo,String sujeto,List<String> roles,String scope) { return Jwt.withTokenValue("prueba").header("alg","RS256").subject(sujeto).claim("tipo",tipo).claim("roles",roles).claim("scope",scope).build(); }

}

