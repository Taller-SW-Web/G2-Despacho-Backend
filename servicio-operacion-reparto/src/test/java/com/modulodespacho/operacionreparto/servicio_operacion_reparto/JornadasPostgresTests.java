package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.ErrorJornada;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure.ConsultaZonaHttp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL aislado y código real. JWT y consulta de zona se identifican como dobles. */
@SpringBootTest(properties="SEGURIDAD_URL=http://127.0.0.1:1")
@Import({TestcontainersConfiguration.class,JornadasPostgresTests.RelojPrueba.class})
@AutoConfigureMockMvc
class JornadasPostgresTests {
    @TestConfiguration(proxyBeanMethods=false) static class RelojPrueba {
        @Bean @Primary Clock relojF06Prueba() { return Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"),ZoneOffset.UTC); }
    }
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @Autowired GestorJornadas gestor;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean ConsultaZonaHttp zonas;
    final UUID actor=UUID.randomUUID(),perfil=UUID.randomUUID(),vehiculo=UUID.randomUUID(),zona=UUID.randomUUID();
    @BeforeEach void preparar() {
        sql.sql("TRUNCATE auditoria_configuracion,proyecciones_despacho,reservas_capacidad,asignaciones_diarias,repartidores,furgonetas").update();
        when(decodificador.decode("gestor")).thenReturn(token("acceso",actor.toString(),""));
        when(decodificador.decode("capacidad")).thenReturn(token("servicio","modulo-despacho","operacion:capacidad:reservar operacion:capacidad:liberar operacion:proyecciones:escribir"));
        sql.sql("""
            INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
              id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
            VALUES (:id,'Jornada','Prueba','12345678','999111222','jornada@example.invalid','TEST-01','COMPLETO','ACTIVO',:actor,'VINCULADO',:actor,:actor)
            """).param("id",perfil).param("actor",actor).update();
        sql.sql("""
            INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
            VALUES (:id,'TEST-01','DISPONIBLE',500,4,80,:actor,:actor)
            """).param("id",vehiculo).param("actor",actor).update();
    }
    @Test void abreConInstantaneaYAuditoriaYRechazaDuplicadosYRecursosInhabilitados() throws Exception {
        UUID jornada=abrir();
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion WHERE accion='APERTURA_JORNADA'").query(Long.class).single()).isEqualTo(1);
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isConflict());
        sql.sql("UPDATE furgonetas SET capacidad_max_kg=300").update();
        http.perform(get("/api/v1/jornadas/"+jornada).header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limites.pesoKg").value(500));
        http.perform(get("/api/v1/repartidores/disponibles").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.repartidores[0].idJornada").value(jornada.toString()));
    }
    @Test void noAbreConPerfilPendienteNiConZonaInactivaOIndisponible() throws Exception {
        sql.sql("UPDATE repartidores SET estado_vinculacion='PENDIENTE',id_usuario_seguridad=NULL").update();
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isUnprocessableEntity());
        sql.sql("UPDATE repartidores SET estado_vinculacion='VINCULADO',id_usuario_seguridad=:usuario").param("usuario",actor).update();
        sql.sql("UPDATE furgonetas SET estado='EN_MANTENIMIENTO'").update();
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isConflict());
        sql.sql("UPDATE furgonetas SET estado='DISPONIBLE'").update();
        doThrow(ErrorJornada.noApto("Zona inactiva")).when(zonas).verificar(eq(zona),anyString());
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isUnprocessableEntity());
        doThrow(ErrorJornada.noDisponible()).when(zonas).verificar(eq(zona),anyString());
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isServiceUnavailable());
        assertThat(sql.sql("SELECT count(*) FROM asignaciones_diarias").query(Long.class).single()).isZero();
    }
    @Test void reservaProyeccionRetornoYCierreCompartenLaMismaOcupacionSinLiberarAnticipadamente() throws Exception {
        UUID jornada=abrir(),despacho=UUID.randomUUID(),clave=UUID.randomUUID();
        var confirmacion=reservar(jornada,despacho,clave,"500",4,80);
        UUID reserva=UUID.fromString(confirmacion.path("idReserva").asText());
        http.perform(get("/api/v1/flota/monitoreo").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenido[0].estadoOperativo").value("SATURADO"));
        http.perform(post("/api/v1/jornadas/"+jornada+"/cierre").header("Authorization","Bearer gestor")).andExpect(status().isConflict());
        proyectar(jornada,reserva,despacho,"ASIGNADO",1);
        http.perform(post("/api/v1/jornadas/"+jornada+"/cierre").header("Authorization","Bearer gestor")).andExpect(status().isConflict());
        proyectar(jornada,reserva,despacho,"FALLIDO",2);
        proyectar(jornada,reserva,despacho,"ASIGNADO",1);
        assertThat(sql.sql("SELECT estado FROM proyecciones_despacho").query(String.class).single()).isEqualTo("FALLIDO");
        http.perform(post("/api/v1/jornadas/"+jornada+"/cierre").header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ocupacion.pesoKg").value(500)).andExpect(jsonPath("$.estado").value("CERRADA"));
        String cierre=sql.sql("SELECT cerrada_en::text FROM asignaciones_diarias").query(String.class).single();
        http.perform(post("/api/v1/jornadas/"+jornada+"/cierre").header("Authorization","Bearer gestor")).andExpect(status().isOk());
        assertThat(sql.sql("SELECT cerrada_en::text FROM asignaciones_diarias").query(String.class).single()).isEqualTo(cierre);
        http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura())).andExpect(status().isConflict());
        String liberar=json.writeValueAsString(Map.of("idDespacho",despacho,"motivo","RECEPCION_EN_CENTRO"));
        http.perform(post("/internal/v1/capacidad/reservas/"+reserva+"/liberacion").header("Authorization","Bearer capacidad").contentType("application/json").content(liberar))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("LIBERADA"));
        http.perform(post("/internal/v1/capacidad/reservas/"+reserva+"/liberacion").header("Authorization","Bearer capacidad").contentType("application/json").content(liberar.replace("RECEPCION_EN_CENTRO","COMPENSACION")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.motivoLiberacion").value("RECEPCION_EN_CENTRO"));
        assertThat(reservar(jornada,despacho,clave,"500",4,80)).isEqualTo(confirmacion);
        http.perform(get("/api/v1/jornadas/"+jornada).header("Authorization","Bearer gestor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CERRADA")).andExpect(jsonPath("$.ocupacion.pesoKg").value(0));
        abrir();
    }
    @Test void reservasConcurrentesNoSuperanCapacidadNiDuplicanAuditoria() throws Exception {
        UUID jornada=abrir();
        try(var ejecutor=Executors.newFixedThreadPool(2)) {
            CountDownLatch inicio=new CountDownLatch(1);
            Callable<Boolean> reservar=() -> {inicio.await();try {gestor.reservar(UUID.randomUUID(),new SolicitudReserva(UUID.randomUUID(),perfil,jornada,
                    new Capacidad(new BigDecimal("300"),BigDecimal.ONE,1)),"modulo-despacho");return true;}catch(ErrorJornada error){assertThat(error.estado()).isEqualTo(422);return false;}};
            var primera=ejecutor.submit(reservar);var segunda=ejecutor.submit(reservar);inicio.countDown();
            assertThat(List.of(primera.get(15,TimeUnit.SECONDS),segunda.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(sql.sql("SELECT sum(peso_kg) FROM reservas_capacidad WHERE estado='ACTIVA'").query(BigDecimal.class).single()).isEqualByComparingTo("300");
    }
    @Test void cierreYReservaConcurrentesNoPuedenConfirmarseAmbos() throws Exception {
        UUID jornada=abrir();
        try(var ejecutor=Executors.newFixedThreadPool(2)) {
            CountDownLatch inicio=new CountDownLatch(1);
            var cierre=ejecutor.submit(() -> {inicio.await();try {gestor.cerrar(jornada,actor,"GESTOR_DESPACHO");return true;}catch(ErrorJornada error){assertThat(error.estado()).isEqualTo(409);return false;}});
            var reserva=ejecutor.submit(() -> {inicio.await();try {gestor.reservar(UUID.randomUUID(),new SolicitudReserva(UUID.randomUUID(),perfil,jornada,new Capacidad(BigDecimal.ONE,BigDecimal.ONE,1)),"modulo-despacho");return true;}catch(ErrorJornada error){assertThat(error.estado()).isEqualTo(409);return false;}});
            inicio.countDown();assertThat(List.of(cierre.get(15,TimeUnit.SECONDS),reserva.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void fallaDeAuditoriaRevierteLaApertura() throws Exception {
        sql.sql("ALTER TABLE auditoria_configuracion RENAME TO auditoria_no_disponible").update();
        try {
            http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura()))
                    .andExpect(status().isServiceUnavailable());
            assertThat(sql.sql("SELECT count(*) FROM asignaciones_diarias").query(Long.class).single()).isZero();
        } finally {sql.sql("ALTER TABLE auditoria_no_disponible RENAME TO auditoria_configuracion").update();}
    }
    @Test void unReintentoDeCierrePropioNoCierraUnaNuevaJornadaDelMismoRepartidor() throws Exception {
        UUID primera=abrir();String cuerpo=json.writeValueAsString(Map.of("idJornada",primera));UUID clave=UUID.randomUUID();
        http.perform(post("/api/v1/repartidor/jornada/cierre").header("Authorization","Bearer gestor").header("Idempotency-Key",clave)
                .contentType("application/json").content(cuerpo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idJornada").value(primera.toString()));
        UUID segunda=abrir();
        http.perform(post("/api/v1/repartidor/jornada/cierre").header("Authorization","Bearer gestor").header("Idempotency-Key",clave)
                .contentType("application/json").content(cuerpo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idJornada").value(primera.toString()));
        assertThat(sql.sql("SELECT estado FROM asignaciones_diarias WHERE id=:id").param("id",segunda).query(String.class).single()).isEqualTo("ACTIVA");
    }
    @Test void exportaRespuestasRealesParaContrastarElConsumidorFrontend() throws Exception {
        UUID jornada=abrir();var salidas=new LinkedHashMap<String,Object>();
        for(var entrada:Map.of("monitoreo","/api/v1/flota/monitoreo","detalle","/api/v1/jornadas/"+jornada,"disponibilidad","/api/v1/repartidores/disponibles").entrySet()) {
            String cuerpo=http.perform(get(entrada.getValue()).header("Authorization","Bearer gestor")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            salidas.put(entrada.getKey(),json.readTree(cuerpo));
        }
        UUID despacho=UUID.randomUUID();var reserva=reservar(jornada,despacho,UUID.randomUUID(),"20",1,2);
        salidas.put("reserva",reserva);
        String liberacion=http.perform(post("/internal/v1/capacidad/reservas/"+reserva.path("idReserva").asText()+"/liberacion")
                .header("Authorization","Bearer capacidad").contentType("application/json")
                .content(json.writeValueAsString(Map.of("idDespacho",despacho,"motivo","COMPENSACION"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        salidas.put("liberacion",json.readTree(liberacion));
        salidas.put("dependencias","PostgreSQL aislado real; JWT y consulta de zona como dobles; datos sintéticos");
        Files.writeString(Path.of("target/f06-respuestas-backend.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(salidas));
    }
    @Test void mideDisponibilidadLocalConMilPerfilesSinAcreditarElSlaDeRender() throws Exception {
        abrir();
        sql.sql("""
            INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,turno_habitual,estado_registro,
              estado_vinculacion,creado_por,actualizado_por)
            SELECT gen_random_uuid(),'Prueba','Rendimiento',lpad((20000000+n)::text,8,'0'),'999111222',
              'rendimiento-'||n||'@example.invalid','TEST-01','COMPLETO','ACTIVO','PENDIENTE',:actor,:actor
              FROM generate_series(1,1000) n
            """).param("actor",actor).update();
        var mediciones=new ArrayList<Double>();
        for(int intento=0;intento<25;intento++) {
            long inicio=System.nanoTime();
            http.perform(get("/api/v1/repartidores/disponibles").header("Authorization","Bearer gestor"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.repartidores.length()").value(1));
            if(intento>=5) mediciones.add((System.nanoTime()-inicio)/1_000_000.0);
        }
        Collections.sort(mediciones);
        Files.writeString(Path.of("target/f06-rendimiento-local.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "entorno","PostgreSQL aislado, MockMvc, JWT doble; no acredita Render", "perfiles",1001,
                "calentamiento",5,"muestras",20,"p95Ms",mediciones.get(18),"tiemposMs",mediciones)));
    }
    private UUID abrir() throws Exception {
        String cuerpo=http.perform(post("/api/v1/jornadas").header("Authorization","Bearer gestor").contentType("application/json").content(apertura()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(cuerpo).path("idJornada").asText());
    }
    private String apertura() {return json.writeValueAsString(Map.of("idRepartidor",perfil,"idFurgoneta",vehiculo,"idZona",zona,"fecha","2026-10-17"));}
    private tools.jackson.databind.JsonNode reservar(UUID jornada,UUID despacho,UUID clave,String peso,int volumen,int paquetes) throws Exception {
        String cuerpo=json.writeValueAsString(Map.of("idDespacho",despacho,"idRepartidor",perfil,"idJornada",jornada,"requerido",Map.of("pesoKg",new BigDecimal(peso),"volumenM3",volumen,"paquetes",paquetes)));
        return json.readTree(http.perform(post("/internal/v1/capacidad/reservas").header("Authorization","Bearer capacidad").header("Idempotency-Key",clave).contentType("application/json").content(cuerpo))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void proyectar(UUID jornada,UUID reserva,UUID despacho,String estado,long version) throws Exception {
        var datos=new Proyeccion(despacho,"PED-PRUEBA","TRK-PRUEBA",estado,jornada,reserva,1,new Destinatario("Persona Prueba","999111222"),
                new Destino("Dirección sintética",null,"Lima","Lima","Prueba","15000",null),LocalDate.of(2026,10,17),0,version,Instant.parse("2026-10-17T15:00:00Z"),2,null,null);
        http.perform(post("/internal/v1/proyecciones/despachos/"+despacho).header("Authorization","Bearer capacidad").contentType("application/json").content(json.writeValueAsString(datos)))
                .andExpect(status().isNoContent());
    }
    private Jwt token(String tipo,String sujeto,String scope) {return Jwt.withTokenValue("prueba").header("alg","RS256").subject(sujeto).claim("tipo",tipo)
            .claim("roles","acceso".equals(tipo)?List.of("GESTOR_DESPACHO"):List.of()).claim("scope",scope).build();}
}
