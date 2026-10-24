package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.application.PuertoGestion;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.campo.domain.ModelosCampo.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** F-03 con PostgreSQL aislado y código real; Gestión, Storage, JWT y zona se identifican como dobles. */
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1", "despacho.evidencias.supabase-url=https://almacen.example.invalid",
        "despacho.evidencias.supabase-service-key=clave-prueba", "despacho.evidencias.bucket=evidencias"})
@Import({TestcontainersConfiguration.class, OperacionCampoPostgresTests.RelojPrueba.class})
@AutoConfigureMockMvc
class OperacionCampoPostgresTests {
    static final Instant AHORA = Instant.parse("2026-10-17T15:41:00Z");
    @TestConfiguration(proxyBeanMethods = false) static class RelojPrueba {
        @Bean @Primary Clock relojF03Prueba() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean ConsultaZonaHttp zonas;
    @MockitoBean PuertoGestion gestion;
    @MockitoBean VerificadorCargaStorage storage;
    final UUID actor = UUID.randomUUID(), perfil = UUID.randomUUID(), vehiculo = UUID.randomUUID(), zona = UUID.randomUUID();
    UUID jornada;

    @BeforeEach void preparar() throws Exception {
        sql.sql("TRUNCATE comandos_repartidor,evidencias,auditoria_configuracion,proyecciones_despacho,reservas_capacidad,asignaciones_diarias,repartidores,furgonetas").update();
        when(decodificador.decode("repartidor")).thenReturn(token("acceso", actor.toString(), "", List.of()));
        when(decodificador.decode("gestor")).thenReturn(token("acceso", actor.toString(), "", List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("capacidad")).thenReturn(token("servicio", "modulo-despacho",
                "operacion:capacidad:reservar operacion:capacidad:liberar operacion:proyecciones:escribir", List.of()));
        sql.sql("""
            INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
              id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
            VALUES (:id,'Martín','Rojas','12345678','999111222','mrojas@example.invalid','TEST-01','COMPLETO','ACTIVO',:actor,'VINCULADO',:actor,:actor)
            """).param("id", perfil).param("actor", actor).update();
        sql.sql("""
            INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
            VALUES (:id,'TEST-01','DISPONIBLE',500,4,80,:actor,:actor)
            """).param("id", vehiculo).param("actor", actor).update();
        String apertura = json.writeValueAsString(Map.of("idRepartidor", perfil, "idFurgoneta", vehiculo, "idZona", zona, "fecha", "2026-10-17"));
        jornada = UUID.fromString(json.readTree(http.perform(post("/api/v1/jornadas").header("Authorization", "Bearer gestor")
                .contentType("application/json").content(apertura)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).path("idJornada").asString());
        // Gestión simulado: acepta y devuelve la versión siguiente con los hitos del ciclo.
        when(gestion.transicionar(any(), any(), any())).thenAnswer(llamada -> {
            SolicitudTransicion s = llamada.getArgument(2);
            boolean fallo = "FALLIDO".equals(s.estadoDestino());
            return new RespuestaTransicion(llamada.getArgument(0).toString(), "ASIGNADO", s.estadoDestino(), 9,
                    fallo && !"NO_INTENTADO".equals(s.motivo()) ? 1 : 0, s.motivo(),
                    new Hitos(AHORA.minusSeconds(9000), "EN_CAMINO".equals(s.estadoDestino()) ? AHORA : AHORA.minusSeconds(600),
                            "ENTREGADO".equals(s.estadoDestino()) ? AHORA : null, fallo ? AHORA : null), AHORA);
        });
    }

    @Test void iniciaTrasladoUnaSolaVezAunqueSeReintenteLaMismaClave() throws Exception {
        UUID despacho = despacho("ASIGNADO"); UUID clave = UUID.randomUUID();
        String original = comando(despacho, "inicio-traslado", clave, null).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.estado").value("EN_CAMINO")).andExpect(jsonPath("$.destinatario.nombre").value("Carlos Quispe"))
                .andExpect(jsonPath("$.hitos.enCaminoEn").value("2026-10-17T15:41:00Z")).andReturn().getResponse().getContentAsString();
        comando(despacho, "inicio-traslado", clave, null).andExpect(status().isOk()).andExpect(content().json(original));
        comando(despacho, "inicio-traslado", UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        verify(gestion, times(1)).transicionar(any(), any(), any());
        http.perform(get("/api/v1/repartidor/mi-ruta").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos[0].estado").value("EN_CAMINO"))
                .andExpect(jsonPath("$.despachos[0].hitos.asignadoEn").value("2026-10-17T13:11:00Z"));
    }

    @Test void entregaConFotoVerificadaAsociaLaEvidenciaYOcultaDatosPersonales() throws Exception {
        UUID despacho = despacho("EN_CAMINO"); UUID evidencia = evidencia(despacho, "ENTREGA");
        when(storage.verificar("evidencias/" + despacho + "/" + evidencia))
                .thenReturn(Optional.of(new VerificadorCargaStorage.ObjetoCargado(612_000, "image/jpeg")));
        comando(despacho, "entrega", UUID.randomUUID(), Map.of("idEvidencia", evidencia, "recibidoPor", "Carlos Quispe"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.destinatario").doesNotExist()).andExpect(jsonPath("$.destino").doesNotExist())
                .andExpect(jsonPath("$.hitos.entregadoEn").value("2026-10-17T15:41:00Z"));
        assertThat(texto("SELECT estado FROM evidencias")).isEqualTo("ASOCIADA");
        // §5.3: la proyección terminal no libera; Gestión solicita la liberación por §5.2.
        assertThat(texto("SELECT estado FROM reservas_capacidad")).isEqualTo("ACTIVA");
    }

    @Test void unaFotoSinSubirNoCambiaElEstadoYLaClavePuedeReintentarse() throws Exception {
        UUID despacho = despacho("EN_CAMINO"); UUID evidencia = evidencia(despacho, "FALLO"); UUID clave = UUID.randomUUID();
        var cuerpo = Map.<String, Object>of("motivo", "CLIENTE_AUSENTE", "idEvidencia", evidencia);
        when(storage.verificar(anyString())).thenReturn(Optional.empty());
        comando(despacho, "fallo", clave, cuerpo).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_EVIDENCIA_NO_CARGADA"));
        verifyNoInteractions(gestion);
        when(storage.verificar(anyString())).thenReturn(Optional.of(new VerificadorCargaStorage.ObjetoCargado(400_000, "image/jpeg")));
        comando(despacho, "fallo", clave, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDO"))
                .andExpect(jsonPath("$.motivoFallo").value("CLIENTE_AUSENTE")).andExpect(jsonPath("$.numeroIntentos").value(1));
        assertThat(texto("SELECT estado FROM reservas_capacidad")).isEqualTo("ACTIVA");
    }

    @Test void unaFotoDeOtroDespachoOTipoNoSePuedeAsociar() throws Exception {
        UUID despacho = despacho("EN_CAMINO"); UUID ajena = evidencia(despacho, "FALLO");
        comando(despacho, "entrega", UUID.randomUUID(), Map.of("idEvidencia", ajena)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_EVIDENCIA_INVALIDA"));
        comando(despacho, "fallo", UUID.randomUUID(), Map.of("motivo", "NO_INTENTADO", "idEvidencia", ajena))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_MOTIVO_INVALIDO"));
        verifyNoInteractions(gestion, storage);
    }

    @Test void unaCaidaDeGestionRespondeServicioNoDisponibleSinCambiarLaProyeccion() throws Exception {
        UUID despacho = despacho("ASIGNADO"); UUID clave = UUID.randomUUID();
        doThrow(new ErrorJornada(503, "DESP_ERROR_GESTION_NO_DISPONIBLE", "Reintenta")).when(gestion).transicionar(any(), any(), any());
        comando(despacho, "inicio-traslado", clave, null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_GESTION_NO_DISPONIBLE"));
        assertThat(texto("SELECT estado FROM proyecciones_despacho")).isEqualTo("ASIGNADO");
        assertThat(texto("SELECT estado FROM comandos_repartidor")).isEqualTo("EN_PROCESO");
    }

    @Test void elCierreDejaPendientesComoNoIntentadoYElResumenListaLaDevolucion() throws Exception {
        despacho("ASIGNADO"); despacho("EN_CAMINO"); UUID entregado = despacho("EN_CAMINO");
        UUID evidencia = evidencia(entregado, "ENTREGA");
        when(storage.verificar(anyString())).thenReturn(Optional.of(new VerificadorCargaStorage.ObjetoCargado(100_000, "image/jpeg")));
        comando(entregado, "entrega", UUID.randomUUID(), Map.of("idEvidencia", evidencia)).andExpect(status().isOk());
        http.perform(post("/api/v1/repartidor/jornada/cierre").header("Authorization", "Bearer repartidor").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json.writeValueAsString(Map.of("idJornada", jornada))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"));
        http.perform(get("/api/v1/repartidor/jornada/resumen").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoJornada").value("CERRADA")).andExpect(jsonPath("$.cerradaEn").value("2026-10-17T15:41:00Z"))
                .andExpect(jsonPath("$.asignados").value(3)).andExpect(jsonPath("$.entregados").value(1))
                .andExpect(jsonPath("$.noIntentados").value(2)).andExpect(jsonPath("$.pendientes").value(0))
                .andExpect(jsonPath("$.paquetesPorDevolver.length()").value(2))
                .andExpect(jsonPath("$.paquetesPorDevolver[0].motivoFallo").value("NO_INTENTADO"))
                .andExpect(jsonPath("$.paquetesPorDevolver[0].direccion").value("Jr. Pumacahua 1180"));
        http.perform(get("/api/v1/repartidor/mi-ruta").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoOperativo").value("FUERA_DE_TURNO"));
    }

    @Test void losComandosExigenClaveUuidV4YPerfilDeCampo() throws Exception {
        UUID despacho = despacho("ASIGNADO");
        http.perform(post("/api/v1/repartidor/despachos/" + despacho + "/inicio-traslado").header("Authorization", "Bearer repartidor"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/v1/repartidor/despachos/" + despacho + "/inicio-traslado").header("Authorization", "Bearer repartidor")
                .header("Idempotency-Key", UUID.nameUUIDFromBytes(new byte[] {1}))).andExpect(status().isBadRequest());
        sql.sql("UPDATE repartidores SET estado_registro='INACTIVO'").update();
        comando(despacho, "inicio-traslado", UUID.randomUUID(), null).andExpect(status().isForbidden());
        verifyNoInteractions(gestion);
    }

    private ResultActions comando(UUID despacho, String accion, UUID clave, Map<String, Object> cuerpo) throws Exception {
        var solicitud = post("/api/v1/repartidor/despachos/" + despacho + "/" + accion).header("Authorization", "Bearer repartidor")
                .header("Idempotency-Key", clave);
        if (cuerpo != null) solicitud.contentType("application/json").content(json.writeValueAsString(cuerpo));
        return http.perform(solicitud);
    }
    private UUID despacho(String estado) throws Exception {
        UUID despacho = UUID.randomUUID();
        String reserva = json.readTree(http.perform(post("/internal/v1/capacidad/reservas").header("Authorization", "Bearer capacidad")
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of(
                        "idDespacho", despacho, "idRepartidor", perfil, "idJornada", jornada,
                        "requerido", Map.of("pesoKg", new BigDecimal("2.5"), "volumenM3", new BigDecimal("0.02"), "paquetes", 1)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("idReserva").asString();
        var datos = new Proyeccion(despacho, "PED-" + despacho, "TRK-78904", estado, jornada, UUID.fromString(reserva), 2,
                new Destinatario("Carlos Quispe", "+51987654321"), new Destino("Jr. Pumacahua 1180", "Frente al parque", null, null, "Jesús María", null, null),
                LocalDate.of(2026, 10, 17), 0, 3, AHORA.minusSeconds(3600), 2, null,
                new Hitos(Instant.parse("2026-10-17T13:11:00Z"), "EN_CAMINO".equals(estado) ? AHORA.minusSeconds(600) : null, null, null));
        http.perform(post("/internal/v1/proyecciones/despachos/" + despacho).header("Authorization", "Bearer capacidad")
                .contentType("application/json").content(json.writeValueAsString(datos))).andExpect(status().isNoContent());
        return despacho;
    }
    private UUID evidencia(UUID despacho, String tipo) {
        UUID id = UUID.randomUUID();
        sql.sql("""
            INSERT INTO evidencias(id,despacho_id_externo,ruta_objeto,tipo,tipo_mime,tamano_bytes,estado,cargado_por,autorizada_en,expira_en)
            VALUES (:id,:despacho,:ruta,:tipo,'image/jpeg',612000,'AUTORIZADA',:usuario,now(),now() + interval '2 hours')
            """).param("id", id).param("despacho", despacho).param("ruta", "evidencias/" + despacho + "/" + id)
                .param("tipo", tipo).param("usuario", actor.toString()).update();
        return id;
    }
    private String texto(String consulta) { return sql.sql(consulta).query(String.class).single(); }
    private Jwt token(String tipo, String sujeto, String scope, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto).claim("tipo", tipo).claim("roles", roles).claim("scope", scope).build();
    }
}
