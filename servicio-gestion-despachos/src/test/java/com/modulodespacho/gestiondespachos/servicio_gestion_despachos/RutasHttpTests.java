package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL/Flyway/transacciones reales; identidad y consulta de jornada son dobles explícitos. */
@SpringBootTest(properties={"despacho.programacion.recuperacion-habilitada=false", "SEGURIDAD_URL=http://127.0.0.1:1"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RutasHttpTests {
    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @Autowired JsonMapper json;
    @MockitoBean JwtDecoder identidad;
    @MockitoBean LectorDatosOperativos operacion;
    final String jornada = UUID.randomUUID().toString();
    final UUID uno = UUID.randomUUID(), dos = UUID.randomUUID(), fijo = UUID.randomUUID();

    @BeforeEach void preparar() {
        sql.sql("DROP TRIGGER IF EXISTS prueba_fallo_ruta ON historial_estados_despacho").update();
        sql.sql("TRUNCATE despachos,resultados_secuencia_ruta CASCADE").update();
        when(identidad.decode("gestor")).thenReturn(token("acceso", List.of("GESTOR_DESPACHO")));
        when(identidad.decode("otro")).thenReturn(token("acceso", List.of()));
        when(identidad.decode("servicio")).thenReturn(token("servicio", List.of("GESTOR_DESPACHO")));
        when(operacion.consultar(any(), any())).thenReturn(new DatosOperativos(Map.of(), Map.of(jornada,
                new Jornada(jornada, EstadoJornada.ACTIVA, null, Instant.now()))));
        insertar(uno, 1, "ASIGNADO"); insertar(fijo, 2, "EN_CAMINO"); insertar(dos, 3, "ASIGNADO");
    }
    @Test void consultaOrdenVersionEstadoYNoExponeSinPermisos() throws Exception {
        http.perform(get(ruta()).header("Authorization","Bearer gestor")).andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.despachos[0].idDespacho").value(uno.toString()))
                .andExpect(jsonPath("$.despachos[1].estado").value("EN_CAMINO"));
        http.perform(get(ruta())).andExpect(status().isUnauthorized());
        for (String token : List.of("otro","servicio")) {
            http.perform(get(ruta()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            http.perform(put(secuencia()).header("Authorization","Bearer "+token).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void intercambiaAsignadosConPosicionFijaAuditoriaYOutboxSinEventoComercial() throws Exception {
        String version = version();
        String clave = UUID.randomUUID().toString();
        String respuesta = guardar(version, clave, cuerpo(3,2,1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos[0].idDespacho").value(dos.toString())).andReturn().getResponse().getContentAsString();
        assertThat(sql.sql("SELECT count(*) FROM historial_estados_despacho WHERE tipo_operacion='REORDENAMIENTO'").query(Long.class).single()).isEqualTo(2);
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes WHERE destino='OPERACION'").query(Long.class).single()).isEqualTo(2);
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes WHERE destino='VENTAS'").query(Long.class).single()).isZero();
        assertThat(sql.sql("SELECT version FROM despachos WHERE id=:id").param("id",fijo).query(Integer.class).single()).isEqualTo(1);
        // La respuesta perdida se recupera sin consultar siquiera el proveedor ya caído.
        when(operacion.consultar(any(),any())).thenThrow(new IllegalStateException("Proveedor caído"));
        guardar(version, clave, cuerpo(3,2,1)).andExpect(status().isOk()).andExpect(content().json(respuesta));
        guardar(version, clave, cuerpo(1,2,3)).andExpect(status().isConflict());
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes").query(Long.class).single()).isEqualTo(2);
    }
    @Test void rechazaDuplicadosOmisionesYAjenosSinCambios() throws Exception {
        String version=version();
        guardar(version,UUID.randomUUID().toString(),cuerpo(1,2,2)).andExpect(status().isBadRequest());
        guardar(version,UUID.randomUUID().toString(),"{\"despachos\":[]}").andExpect(status().isBadRequest());
        guardar(version,UUID.randomUUID().toString(),cuerpo(1,2,3).replace(dos.toString(),UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        guardar(version,UUID.randomUUID().toString(),cuerpo(2,1,3)).andExpect(status().isConflict());
        for(String estado:List.of("ENTREGADO","FALLIDO","DEVUELTO_A_ORIGEN")) {
            sql.sql("UPDATE despachos SET estado=:estado,version=version+1 WHERE id=:id").param("estado",estado).param("id",fijo).update();
            guardar(version(),UUID.randomUUID().toString(),cuerpo(2,1,3)).andExpect(status().isConflict());
        }
        http.perform(put(secuencia()).header("Authorization","Bearer gestor").contentType("application/json").content(cuerpo(1,2,3)))
                .andExpect(status().isBadRequest());
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes").query(Long.class).single()).isZero();
    }
    @Test void unaFotografiaViejaDetectaInicioDeTrasladoYNoMueveNada() throws Exception {
        String version=version();
        sql.sql("UPDATE despachos SET estado='EN_CAMINO',version=version+1 WHERE id=:id").param("id",uno).update();
        guardar(version,UUID.randomUUID().toString(),cuerpo(3,2,1)).andExpect(status().isConflict());
        assertThat(posicion(uno)).isEqualTo(1);
    }
    @Test void canceladoSeRetiraDeLaFotografiaSinBorrarSuPosicionHistorica() throws Exception {
        String anterior = version();
        sql.sql("UPDATE despachos SET estado='CANCELADO',version=version+1 WHERE id=:id").param("id", fijo).update();
        http.perform(get(ruta()).header("Authorization","Bearer gestor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.despachos.length()").value(2)).andExpect(jsonPath("$.despachos[1].posicion").value(3));
        guardar(anterior,UUID.randomUUID().toString(),cuerpo(2,1,3)).andExpect(status().isConflict());
        guardar(version(),UUID.randomUUID().toString(),cuerpo(2,1,3)).andExpect(status().isBadRequest());
        assertThat(posicion(fijo)).isEqualTo(2);
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes").query(Long.class).single()).isZero();
    }
    @Test void dosGuardadosConcurrentesSobreLaMismaFotografiaNoSePisan() throws Exception {
        String version=version();
        try(var hilos=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> tarea=() -> guardar(version,UUID.randomUUID().toString(),cuerpo(3,2,1)).andReturn().getResponse().getStatus();
            var a=hilos.submit(tarea); var b=hilos.submit(tarea);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void falloDeAuditoriaReviertePosicionesVersionesResultadoYOutbox() throws Exception {
        String version=version();
        sql.sql("CREATE OR REPLACE FUNCTION prueba_fallo_ruta() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fallo inducido'; END $$").update();
        sql.sql("CREATE TRIGGER prueba_fallo_ruta BEFORE INSERT ON historial_estados_despacho FOR EACH ROW EXECUTE FUNCTION prueba_fallo_ruta()").update();
        guardar(version,UUID.randomUUID().toString(),cuerpo(3,2,1)).andExpect(status().is5xxServerError());
        assertThat(posicion(uno)).isEqualTo(1);
        assertThat(version()).isEqualTo(version);
        assertThat(sql.sql("SELECT count(*) FROM resultados_secuencia_ruta").query(Long.class).single()).isZero();
        assertThat(sql.sql("SELECT count(*) FROM eventos_salientes").query(Long.class).single()).isZero();
    }
    @Test void conservaHuecosHistoricosYJornadaCerradaEsSoloLectura() throws Exception {
        sql.sql("DELETE FROM despachos WHERE id=:id").param("id",fijo).update();
        String cuerpo=json.writeValueAsString(Map.of("despachos",List.of(Map.of("idDespacho",uno,"posicion",3),Map.of("idDespacho",dos,"posicion",1))));
        guardar(version(),UUID.randomUUID().toString(),cuerpo).andExpect(status().isOk());
        when(operacion.consultar(any(),any())).thenReturn(new DatosOperativos(Map.of(),Map.of(jornada,new Jornada(jornada,EstadoJornada.CERRADA,Instant.now(),Instant.now()))));
        guardar(version(),UUID.randomUUID().toString(),cuerpo).andExpect(status().isConflict());
    }
    private String ruta() { return "/api/v1/jornadas/"+jornada+"/ruta"; }
    private String secuencia() { return "/api/v1/jornadas/"+jornada+"/secuencia"; }
    private String version() throws Exception { return http.perform(get(ruta()).header("Authorization","Bearer gestor")).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag"); }
    private ResultActions guardar(String version,String clave,String cuerpo) throws Exception {
        return http.perform(put(secuencia()).header("Authorization","Bearer gestor").header("If-Match",version).header("Idempotency-Key",clave).contentType("application/json").content(cuerpo));
    }
    private String cuerpo(int a,int b,int c) { return json.writeValueAsString(Map.of("despachos",List.of(Map.of("idDespacho",uno,"posicion",a),Map.of("idDespacho",fijo,"posicion",b),Map.of("idDespacho",dos,"posicion",c)))); }
    private int posicion(UUID id) { return sql.sql("SELECT secuencia_ruta FROM despachos WHERE id=:id").param("id",id).query(Integer.class).single(); }
    private void insertar(UUID id,int posicion,String estado) {
        sql.sql("""
                INSERT INTO despachos(id,pedido_id,codigo_operativo,codigo_rastreo_interno,es_simulado,estado,destinatario_nombre,
                    destinatario_telefono,direccion,distrito,peso_kg,volumen_m3,cantidad_paquetes,fecha_programada,
                    repartidor_id_externo,asignacion_diaria_id_externa,reserva_capacidad_id_externa,secuencia_ruta)
                VALUES (:id,:pedido,:codigo,:rastreo,true,:estado,'Prueba','999111222','Dirección sintética','Lima',2,0.01,1,
                    current_date,'repartidor-prueba',:jornada,:reserva,:posicion)
                """).param("id",id).param("pedido",id.toString()).param("codigo","DSP-"+posicion).param("rastreo","TRK-"+posicion)
                .param("estado",estado).param("jornada",jornada).param("reserva",UUID.randomUUID().toString()).param("posicion",posicion).update();
    }
    private Jwt token(String tipo,List<String> roles) { return Jwt.withTokenValue(tipo).header("alg","RS256").subject("gestor-rutas")
            .claim("tipo",tipo).claim("roles",roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build(); }
}
