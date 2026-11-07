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
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.VerificadorCargaStorage;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ES-F06-09 y ES-F03-09 con PostgreSQL aislado; Gestión, Storage, JWT y zona se identifican como dobles. */
@SpringBootTest(properties = {"SEGURIDAD_URL=http://127.0.0.1:1", "despacho.evidencias.supabase-url=https://almacen.example.invalid",
        "despacho.evidencias.supabase-service-key=clave-prueba", "despacho.evidencias.bucket=evidencias"})
@Import({TestcontainersConfiguration.class, RecojosPostgresTests.RelojPrueba.class})
@AutoConfigureMockMvc
class RecojosPostgresTests {
    static final Instant AHORA = Instant.parse("2026-10-17T15:41:00Z");
    static final String HOY = "2026-10-17";
    @TestConfiguration(proxyBeanMethods = false) static class RelojPrueba {
        @Bean @Primary Clock relojRecojosPrueba() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean ConsultaZonaHttp zonas;
    @MockitoBean PuertoGestion gestion;
    @MockitoBean VerificadorCargaStorage storage;
    @MockitoBean AutorizadorCargaStorage autorizadorStorage;
    final UUID actor = UUID.randomUUID(), zona = UUID.randomUUID();
    UUID perfil, vehiculo;

    @BeforeEach void preparar() {
        sql.sql("TRUNCATE comandos_repartidor,evidencias,auditoria_configuracion,proyecciones_recojo,proyecciones_despacho,reservas_capacidad,asignaciones_diarias,repartidores,furgonetas").update();
        when(decodificador.decode("repartidor")).thenReturn(token("acceso", actor.toString(), List.of()));
        when(decodificador.decode("gestor")).thenReturn(token("acceso", UUID.randomUUID().toString(), List.of("GESTOR_DESPACHO")));
        when(decodificador.decode("capacidad")).thenReturn(token("servicio", "modulo-despacho", List.of()));
        perfil = repartidor(actor, "12345678", "uno@example.invalid");
        vehiculo = furgoneta("TEST-01");
        when(gestion.transicionarRecojo(any(), any(), any())).thenAnswer(llamada -> {
            SolicitudTransicionRecojo s = llamada.getArgument(2);
            boolean fallo = "FALLIDO".equals(s.estadoDestino());
            return new RespuestaTransicionRecojo(llamada.getArgument(0).toString(), "ASIGNADO", s.estadoDestino(), 9,
                    fallo && !"NO_INTENTADO".equals(s.motivo()) ? 1 : 0, s.motivo(),
                    new HitosRecojo(AHORA.minusSeconds(9000), "EN_CAMINO".equals(s.estadoDestino()) ? AHORA : AHORA.minusSeconds(600),
                            "RECOGIDO".equals(s.estadoDestino()) ? AHORA : null, fallo ? AHORA : null), AHORA);
        });
    }

    @Test void laJornadaGuardaSuTipoYLasAnterioresQuedanComoEntregas() throws Exception {
        abrir(perfil, vehiculo, "RECOJOS").andExpect(status().isCreated()).andExpect(jsonPath("$.tipoOperacion").value("RECOJOS"));
        http.perform(get("/api/v1/flota/monitoreo").header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].jornada.tipoOperacion").value("RECOJOS"));
        UUID otro = repartidor(UUID.randomUUID(), "87654321", "dos@example.invalid");
        abrir(otro, furgoneta("TEST-02"), null).andExpect(status().isCreated()).andExpect(jsonPath("$.tipoOperacion").value("ENTREGAS"));
        abrir(repartidor(UUID.randomUUID(), "11112222", "tres@example.invalid"), furgoneta("TEST-03"), "MIXTA").andExpect(status().isBadRequest());
        assertThat(sql.sql("SELECT count(*) FROM asignaciones_diarias").query(Integer.class).single()).isEqualTo(2);
    }

    @Test void laDisponibilidadSeFiltraPorTipoYSinParametroSoloOfreceEntregas() throws Exception {
        abrir(perfil, vehiculo, "RECOJOS").andExpect(status().isCreated());
        abrir(repartidor(UUID.randomUUID(), "87654321", "dos@example.invalid"), furgoneta("TEST-02"), "ENTREGAS").andExpect(status().isCreated());
        disponibles("").andExpect(jsonPath("$.repartidores.length()").value(1)).andExpect(jsonPath("$.repartidores[0].tipoOperacion").value("ENTREGAS"));
        disponibles("?tipoOperacion=RECOJOS").andExpect(jsonPath("$.repartidores.length()").value(1))
                .andExpect(jsonPath("$.repartidores[0].idRepartidor").value(perfil.toString()));
        http.perform(get("/api/v1/repartidores/disponibles?tipoOperacion=MIXTA").header("Authorization", "Bearer gestor")).andExpect(status().isBadRequest());
    }

    @Test void unaReservaOTransferenciaDeOtroTipoSeRechazaSinEfectos() throws Exception {
        UUID jornadaRecojos = jornada(perfil, vehiculo, "RECOJOS");
        UUID otro = repartidor(UUID.randomUUID(), "87654321", "dos@example.invalid");
        UUID jornadaEntregas = jornada(otro, furgoneta("TEST-02"), "ENTREGAS");
        reservar(UUID.randomUUID(), UUID.randomUUID(), perfil, jornadaRecojos, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_TIPO_JORNADA_INCOMPATIBLE"));
        reservar(UUID.randomUUID(), UUID.randomUUID(), otro, jornadaEntregas, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_TIPO_JORNADA_INCOMPATIBLE"));
        assertThat(sql.sql("SELECT count(*) FROM reservas_capacidad").query(Integer.class).single()).isZero();

        UUID despacho = UUID.randomUUID(), recojo = UUID.randomUUID();
        String reserva = id(reservar(UUID.randomUUID(), despacho, perfil, jornadaRecojos, recojo).andExpect(status().isOk()), "idReserva");
        http.perform(post("/internal/v1/capacidad/transferencias").header("Authorization", "Bearer capacidad").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json.writeValueAsString(Map.of("idReservaOrigen", reserva, "idDespacho", despacho,
                        "idNuevoRepartidor", otro, "idNuevaJornada", jornadaEntregas))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_TIPO_JORNADA_INCOMPATIBLE"));
        assertThat(sql.sql("SELECT estado FROM reservas_capacidad").query(String.class).single()).isEqualTo("ACTIVA");
    }

    @Test void unaJornadaDeRecojosMuestraSoloRecojosConSusDatosPersonalesVisiblesAlOperar() throws Exception {
        UUID jornada = jornada(perfil, vehiculo, "RECOJOS");
        UUID recojo = recojo(jornada, "ASIGNADO");
        http.perform(get("/api/v1/repartidor/mi-ruta").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoOperacion").value("RECOJOS")).andExpect(jsonPath("$.despachos.length()").value(0))
                .andExpect(jsonPath("$.recojos[0].idRecojo").value(recojo.toString())).andExpect(jsonPath("$.recojos[0].destinatario").value("Ana Mendoza"))
                .andExpect(jsonPath("$.recojos[0].lineas[0].sku").value("ZAP-42"));
        http.perform(get("/api/v1/repartidor/recojos/" + recojo).header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.destinatario.telefono").value("+51999000111")).andExpect(jsonPath("$.destino.referencia").value("Portón azul"));
        http.perform(get("/api/v1/jornadas/" + jornada).header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoOperacion").value("RECOJOS")).andExpect(jsonPath("$.recojos[0].idRecojo").value(recojo.toString()));
    }

    @Test void unaJornadaDeEntregasNoExponeRecojosAjenos() throws Exception {
        UUID ajeno = repartidor(UUID.randomUUID(), "87654321", "dos@example.invalid");
        UUID recojo = recojo(jornada(ajeno, furgoneta("TEST-02"), "RECOJOS"), "ASIGNADO");
        jornada(perfil, vehiculo, "ENTREGAS");
        http.perform(get("/api/v1/repartidor/mi-ruta").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoOperacion").value("ENTREGAS")).andExpect(jsonPath("$.recojos.length()").value(0));
        http.perform(get("/api/v1/repartidor/recojos/" + recojo).header("Authorization", "Bearer repartidor")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_RECOJO_NO_ENCONTRADO"));
        comando(recojo, "inicio-traslado", UUID.randomUUID(), null).andExpect(status().isNotFound());
        verifyNoInteractions(gestion);
    }

    @Test void iniciaUnaSolaVezAunqueSeReintenteLaMismaClave() throws Exception {
        UUID recojo = recojo(jornada(perfil, vehiculo, "RECOJOS"), "ASIGNADO"), clave = UUID.randomUUID();
        String original = comando(recojo, "inicio-traslado", clave, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_CAMINO")).andReturn().getResponse().getContentAsString();
        comando(recojo, "inicio-traslado", clave, null).andExpect(status().isOk()).andExpect(content().json(original));
        comando(recojo, "inicio-traslado", UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ESTADO_INCOMPATIBLE"));
        verify(gestion, times(1)).transicionarRecojo(any(), any(), any());
    }

    @Test void elRecojoConFotoVerificadaAsociaLaEvidenciaYOcultaDatosPersonales() throws Exception {
        UUID recojo = recojo(jornada(perfil, vehiculo, "RECOJOS"), "EN_CAMINO"), evidencia = evidencia(recojo, "RECOJO");
        when(storage.verificar(anyString())).thenReturn(Optional.of(new VerificadorCargaStorage.ObjetoCargado(600_000, "image/jpeg")));
        comando(recojo, "recogido", UUID.randomUUID(), Map.of("idEvidencia", evidencia)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECOGIDO")).andExpect(jsonPath("$.destinatario").doesNotExist())
                .andExpect(jsonPath("$.hitos.recogidoEn").value("2026-10-17T15:41:00Z"));
        assertThat(sql.sql("SELECT estado FROM evidencias").query(String.class).single()).isEqualTo("ASOCIADA");
        assertThat(sql.sql("SELECT estado FROM reservas_capacidad").query(String.class).single()).isEqualTo("ACTIVA");
    }

    @Test void unaFotoSinSubirODeOtroTipoNoCambiaElEstado() throws Exception {
        UUID recojo = recojo(jornada(perfil, vehiculo, "RECOJOS"), "EN_CAMINO"), evidencia = evidencia(recojo, "FALLO_RECOJO");
        comando(recojo, "recogido", UUID.randomUUID(), Map.of("idEvidencia", evidencia)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_EVIDENCIA_INVALIDA"));
        when(storage.verificar(anyString())).thenReturn(Optional.empty());
        comando(recojo, "fallo", UUID.randomUUID(), Map.of("motivo", "CLIENTE_AUSENTE", "idEvidencia", evidencia))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESP_ERROR_EVIDENCIA_NO_CARGADA"));
        verifyNoInteractions(gestion);
    }

    @Test void unRecojoNoRealizadoGuardaElMotivoYNoAdmiteNoIntentadoEnCampo() throws Exception {
        UUID recojo = recojo(jornada(perfil, vehiculo, "RECOJOS"), "EN_CAMINO"), evidencia = evidencia(recojo, "FALLO_RECOJO");
        when(storage.verificar(anyString())).thenReturn(Optional.of(new VerificadorCargaStorage.ObjetoCargado(400_000, "image/jpeg")));
        comando(recojo, "fallo", UUID.randomUUID(), Map.of("motivo", "NO_INTENTADO", "idEvidencia", evidencia))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESP_ERROR_MOTIVO_INVALIDO"));
        comando(recojo, "fallo", UUID.randomUUID(), Map.of("motivo", "CLIENTE_NO_ENTREGA_PRODUCTO", "idEvidencia", evidencia, "comentario", " Se negó "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("FALLIDO"))
                .andExpect(jsonPath("$.motivoFallo").value("CLIENTE_NO_ENTREGA_PRODUCTO")).andExpect(jsonPath("$.numeroIntentos").value(1));
        verify(gestion).transicionarRecojo(eq(recojo), any(), argThat(s -> "Se negó".equals(s.comentario())));
    }

    @Test void elCierreDejaPendientesComoNoIntentadoYElResumenListaLoRecogido() throws Exception {
        UUID jornada = jornada(perfil, vehiculo, "RECOJOS");
        recojo(jornada, "ASIGNADO"); recojo(jornada, "EN_CAMINO");
        UUID recogido = recojo(jornada, "RECOGIDO");
        http.perform(post("/api/v1/repartidor/jornada/cierre").header("Authorization", "Bearer repartidor").header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json.writeValueAsString(Map.of("idJornada", jornada))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA"));
        verify(gestion, times(2)).transicionarRecojo(any(), any(), argThat(s -> "NO_INTENTADO".equals(s.motivo()) && s.idEvidencia() == null));
        http.perform(get("/api/v1/repartidor/jornada/resumen").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoOperacion").value("RECOJOS")).andExpect(jsonPath("$.asignados").value(3))
                .andExpect(jsonPath("$.noIntentados").value(2)).andExpect(jsonPath("$.recojosRealizados").value(1))
                .andExpect(jsonPath("$.recojosNoRealizados").value(0)).andExpect(jsonPath("$.pendientes").value(0))
                .andExpect(jsonPath("$.productosPorEntregar.length()").value(1))
                .andExpect(jsonPath("$.productosPorEntregar[0].idRecojo").value(recogido.toString()));
    }

    @Test void elCierreAdministrativoRechazaRecojosPendientesYElRecursoQuedaOcupadoHastaLiberar() throws Exception {
        UUID jornada = jornada(perfil, vehiculo, "RECOJOS");
        recojo(jornada, "EN_CAMINO");
        http.perform(post("/api/v1/jornadas/" + jornada + "/cierre").header("Authorization", "Bearer gestor")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_CONFLICTO_JORNADA"));
        http.perform(get("/api/v1/flota/monitoreo").header("Authorization", "Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].estadoOperativo").value("EN_RUTA"))
                .andExpect(jsonPath("$.contenido[0].jornada.ocupacion.paquetes").value(1));
    }

    @Test void losMotivosSeFiltranPorAmbito() throws Exception {
        jornada(perfil, vehiculo, "RECOJOS");
        when(gestion.motivos()).thenReturn(List.of(new Motivo("CLIENTE_AUSENTE", "Cliente ausente", true, true, true, "AMBOS"),
                new Motivo("PAQUETE_DANADO", "Paquete dañado", true, true, true, "ENTREGA"),
                new Motivo("PRODUCTO_NO_CORRESPONDE", "No corresponde", true, true, true, "RECOJO"),
                new Motivo("NO_INTENTADO", "No intentado", false, false, true, "AMBOS")));
        http.perform(get("/api/v1/repartidor/motivos-fallo?ambito=RECOJO").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.motivos.length()").value(2)).andExpect(jsonPath("$.motivos[1].codigo").value("PRODUCTO_NO_CORRESPONDE"));
        http.perform(get("/api/v1/repartidor/motivos-fallo").header("Authorization", "Bearer repartidor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.motivos.length()").value(2)).andExpect(jsonPath("$.motivos[1].codigo").value("PAQUETE_DANADO"));
        http.perform(get("/api/v1/repartidor/motivos-fallo?ambito=OTRO").header("Authorization", "Bearer repartidor")).andExpect(status().isBadRequest());
    }

    @Test void laAutorizacionDeFotoExigeRecojoPropioEnCaminoYSuTipo() throws Exception {
        when(autorizadorStorage.autorizar(any(), any())).thenReturn(new AutorizadorCargaStorage.AutorizacionStorage("https://almacen.example.invalid/x", AHORA.plusSeconds(600)));
        UUID jornada = jornada(perfil, vehiculo, "RECOJOS");
        UUID enCamino = recojo(jornada, "EN_CAMINO"), asignado = recojo(jornada, "ASIGNADO");
        autorizar(Map.of("idRecojo", enCamino, "tipo", "RECOJO")).andExpect(status().isOk()).andExpect(jsonPath("$.idEvidencia").exists());
        assertThat(sql.sql("SELECT recojo_id_externo FROM evidencias").query(UUID.class).single()).isEqualTo(enCamino);
        autorizar(Map.of("idRecojo", asignado, "tipo", "RECOJO")).andExpect(status().isNotFound());
        autorizar(Map.of("idRecojo", enCamino, "tipo", "ENTREGA")).andExpect(status().isBadRequest());
        autorizar(Map.of("idRecojo", enCamino, "idDespacho", UUID.randomUUID(), "tipo", "RECOJO")).andExpect(status().isBadRequest());
    }

    private ResultActions autorizar(Map<String, Object> datos) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>(datos);
        cuerpo.put("contentType", "image/jpeg"); cuerpo.put("tamanioBytes", 600_000);
        return http.perform(post("/api/v1/repartidor/evidencias/autorizaciones").header("Authorization", "Bearer repartidor")
                .contentType("application/json").content(json.writeValueAsString(cuerpo)));
    }
    private ResultActions disponibles(String consulta) throws Exception {
        return http.perform(get("/api/v1/repartidores/disponibles" + consulta).header("Authorization", "Bearer gestor")).andExpect(status().isOk());
    }
    private ResultActions comando(UUID recojo, String accion, UUID clave, Map<String, Object> cuerpo) throws Exception {
        var solicitud = post("/api/v1/repartidor/recojos/" + recojo + "/" + accion).header("Authorization", "Bearer repartidor").header("Idempotency-Key", clave);
        if (cuerpo != null) solicitud.contentType("application/json").content(json.writeValueAsString(cuerpo));
        return http.perform(solicitud);
    }
    private ResultActions abrir(UUID repartidor, UUID furgoneta, String tipo) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>(Map.of("idRepartidor", repartidor, "idFurgoneta", furgoneta, "idZona", zona, "fecha", HOY));
        if (tipo != null) cuerpo.put("tipoOperacion", tipo);
        return http.perform(post("/api/v1/jornadas").header("Authorization", "Bearer gestor").contentType("application/json").content(json.writeValueAsString(cuerpo)));
    }
    private UUID jornada(UUID repartidor, UUID furgoneta, String tipo) throws Exception {
        return UUID.fromString(id(abrir(repartidor, furgoneta, tipo).andExpect(status().isCreated()), "idJornada"));
    }
    private ResultActions reservar(UUID clave, UUID despacho, UUID repartidor, UUID jornada, UUID recojo) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>(Map.of("idDespacho", despacho, "idRepartidor", repartidor, "idJornada", jornada,
                "requerido", Map.of("pesoKg", new BigDecimal("2.5"), "volumenM3", new BigDecimal("0.02"), "paquetes", 1)));
        if (recojo != null) cuerpo.put("idRecojo", recojo);
        return http.perform(post("/internal/v1/capacidad/reservas").header("Authorization", "Bearer capacidad").header("Idempotency-Key", clave)
                .contentType("application/json").content(json.writeValueAsString(cuerpo)));
    }
    private UUID recojo(UUID jornada, String estado) throws Exception {
        UUID recojo = UUID.randomUUID(), despacho = UUID.randomUUID();
        UUID reserva = UUID.fromString(id(reservar(UUID.randomUUID(), despacho, perfilDe(jornada), jornada, recojo).andExpect(status().isOk()), "idReserva"));
        var datos = new ProyeccionRecojo(recojo, despacho, "TRK-1", estado, jornada, reserva, 1,
                new Destinatario("Ana Mendoza", "+51999000111"), new Destino("Av. Ejemplo 123", "Portón azul", null, null, "Lince", null, null),
                List.of(new LineaRecojo("ZAP-42", 1)), LocalDate.of(2026, 10, 17), 0, 3, AHORA.minusSeconds(3600), null,
                new HitosRecojo(Instant.parse("2026-10-17T13:11:00Z"), null, null, null));
        http.perform(post("/internal/v1/proyecciones/recojos/" + recojo).header("Authorization", "Bearer capacidad")
                .contentType("application/json").content(json.writeValueAsString(datos))).andExpect(status().isNoContent());
        return recojo;
    }
    private UUID perfilDe(UUID jornada) {
        return sql.sql("SELECT repartidor_id FROM asignaciones_diarias WHERE id=:id").param("id", jornada).query(UUID.class).single();
    }
    private UUID evidencia(UUID recojo, String tipo) {
        UUID id = UUID.randomUUID();
        sql.sql("""
            INSERT INTO evidencias(id,despacho_id_externo,recojo_id_externo,ruta_objeto,tipo,tipo_mime,tamano_bytes,estado,cargado_por,autorizada_en,expira_en)
            VALUES (:id,:despacho,:recojo,:ruta,:tipo,'image/jpeg',612000,'AUTORIZADA',:usuario,now(),now() + interval '2 hours')
            """).param("id", id).param("despacho", UUID.randomUUID()).param("recojo", recojo).param("ruta", "evidencias/" + recojo + "/" + id)
                .param("tipo", tipo).param("usuario", actor.toString()).update();
        return id;
    }
    private UUID repartidor(UUID usuario, String dni, String correo) {
        UUID id = UUID.randomUUID();
        sql.sql("""
            INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
              id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
            VALUES (:id,'Martín','Rojas',:dni,'999111222',:correo,:brevete,'COMPLETO','ACTIVO',:usuario,'VINCULADO',:usuario,:usuario)
            """).param("id", id).param("dni", dni).param("correo", correo).param("brevete", "B-" + dni).param("usuario", usuario).update();
        return id;
    }
    private UUID furgoneta(String placa) {
        UUID id = UUID.randomUUID();
        sql.sql("""
            INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
            VALUES (:id,:placa,'DISPONIBLE',500,4,80,:actor,:actor)
            """).param("id", id).param("placa", placa).param("actor", actor).update();
        return id;
    }
    private String id(ResultActions respuesta, String campo) throws Exception {
        return json.readTree(respuesta.andReturn().getResponse().getContentAsString()).path(campo).asString();
    }
    private Jwt token(String tipo, String sujeto, List<String> roles) {
        return Jwt.withTokenValue("prueba").header("alg", "RS256").subject(sujeto).claim("tipo", tipo).claim("roles", roles).claim("scope", "").build();
    }
}
