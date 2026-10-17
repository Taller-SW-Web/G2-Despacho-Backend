package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.seguridad.ConfiguracionSeguridad;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.shared.errores.RespuestasSeguridad;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.api.ControladorRepartidores;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.GestorFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.api.ControladorConsultaIncidencias;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.application.LectorIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.incidencias.domain.DatosIncidenciasOperativas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.api.ControladorLecturaEvidencia;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizarLecturaEvidencia;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ControladorRepartidores.class, ControladorConsultaIncidencias.class, ControladorLecturaEvidencia.class},
        properties = {"DEMO_SIN_AUTENTICACION=true", "SEGURIDAD_URL="})
@Import({ConfiguracionSeguridad.class, RespuestasSeguridad.class})
class ModoDemoOperacionTests {
    static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Autowired MockMvc http;
    @Autowired ApplicationContext contexto;
    @MockitoBean GestorFlota gestor;
    @MockitoBean LectorIncidenciasOperativas lector;
    @MockitoBean AutorizarLecturaEvidencia evidencias;

    @Test
    void registraConActorUuidYPermisoAdministrativoSinJwt() throws Exception {
        assertThat(contexto.getBeansOfType(JwtDecoder.class)).isEmpty();
        when(gestor.registrarRepartidor(any(), eq(ACTOR))).thenReturn(new Repartidor(ACTOR,
                "Ana", "Demo", "12345678", "999111222", "ana@example.invalid", "BRE-1",
                Turno.COMPLETO, EstadoRegistro.ACTIVO, Vinculacion.PENDIENTE, null));
        http.perform(post("/api/v1/repartidores").contentType("application/json").content("""
                {"nombres":"Ana","apellidos":"Demo","dni":"12345678","telefono":"999111222",
                "correo":"ana@example.invalid","numeroBrevete":"BRE-1","turnoHabitual":"COMPLETO"}
                """)).andExpect(status().isCreated());
        verify(gestor).registrarRepartidor(any(), eq(ACTOR));
    }

    @Test
    void permiteElLoteInternoConIdentidadTecnicaDemo() throws Exception {
        when(lector.consultar(anyList(), anyList()))
                .thenReturn(new DatosIncidenciasOperativas(List.of(), List.of(), Instant.now()));
        http.perform(post("/internal/v1/operacion/consulta-incidencias")
                .contentType("application/json").content("""
                {"idsRepartidor":["repartidor-demo"],"idsJornada":[]}
                """)).andExpect(status().isOk());
        verify(lector).consultar(List.of("repartidor-demo"), List.of());
    }

    @Test
    void permiteAlGestorConsultarEvidenciasSinCambiarSuCasoDeUso() throws Exception {
        when(evidencias.autorizar("foto", ACTOR.toString(), true)).thenReturn(
                new AutorizarLecturaEvidencia.AccesoEvidencia("foto", "despacho",
                        "https://storage.example/demo", Instant.now().plusSeconds(120)));
        http.perform(post("/api/v1/evidencias/foto/acceso")).andExpect(status().isOk())
                .andExpect(jsonPath("$.idDespacho").value("despacho"));
        verify(evidencias).autorizar("foto", ACTOR.toString(), true);
    }
}
