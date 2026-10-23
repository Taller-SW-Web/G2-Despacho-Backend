package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.GestorFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.application.GestorJornadas;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain.DatosJornada;
import static org.assertj.core.api.Assertions.*;

/** Ejecuta los archivos operativos sobre una copia estructural aislada, nunca en Supabase. */
@SpringBootTest(properties="SEGURIDAD_URL=http://127.0.0.1:1")
@ActiveProfiles("render")
@Import({TestcontainersConfiguration.class,JornadasPostgresTests.RelojPrueba.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JornadasHeredadasTests {
    @Autowired DataSource origen;
    @Autowired JdbcClient sql;
    @Autowired GestorFlota flota;
    @Autowired GestorJornadas jornadas;
    @BeforeAll void preparar() throws Exception {
        try(var conexion=origen.getConnection();var sentencia=conexion.createStatement();var recurso=getClass().getResourceAsStream("/flota/esquema-heredado.sql")) {
            sentencia.execute(new String(recurso.readAllBytes(),StandardCharsets.UTF_8));
            sentencia.execute(new ClassPathResource("flota/compatibilidad/F05__compatibilidad_operadores_flota.sql").getContentAsString(StandardCharsets.UTF_8));
            // La inspección de solo estructura detectó esta vista existente en el entorno publicado.
            sentencia.execute("ALTER TABLE proyeccion_despacho ALTER COLUMN pedido_id SET NOT NULL");
            sentencia.execute("CREATE VIEW proyecciones_despacho WITH (security_invoker=true) AS SELECT * FROM proyeccion_despacho");
            sentencia.execute(new ClassPathResource("flota/compatibilidad/F06__jornadas_capacidad_heredada.sql").getContentAsString(StandardCharsets.UTF_8));
            sentencia.execute(new ClassPathResource("flota/compatibilidad/F03__maximo_intentos_heredado.sql").getContentAsString(StandardCharsets.UTF_8));
            sentencia.execute(new ClassPathResource("flota/compatibilidad/F03__maximo_intentos_heredado.sql").getContentAsString(StandardCharsets.UTF_8));
        }
    }
    @Test void conectaCatalogosJornadasReservasYProyeccionesSinRenombrarTablasOriginales() {
        UUID actor=UUID.randomUUID();
        Repartidor perfil=flota.registrarRepartidor(new AltaRepartidor("Jornada","Heredada","12345678",new Contacto("999111222","heredada@example.invalid","TEST-01",Turno.COMPLETO)),actor);
        flota.vincular(perfil.idRepartidor(),actor,actor);
        Furgoneta vehiculo=flota.registrarFurgoneta("TEST-01",new Capacidad(new BigDecimal("500"),new BigDecimal("4"),80),actor);
        var jornada=jornadas.abrir(new DatosJornada.Apertura(perfil.idRepartidor(),vehiculo.idFurgoneta(),UUID.randomUUID(),LocalDate.of(2026,10,17)),actor);
        UUID despacho=UUID.randomUUID();
        var solicitud=new DatosJornada.SolicitudReserva(despacho,perfil.idRepartidor(),jornada.idJornada(),new DatosJornada.Capacidad(BigDecimal.TEN,BigDecimal.ONE,1));
        var reserva=jornadas.reservar(UUID.randomUUID(),solicitud,"modulo-despacho");
        jornadas.proyectar(despacho,new DatosJornada.Proyeccion(despacho,"PED-HEREDADO","TRK-PRUEBA","FALLIDO",jornada.idJornada(),reserva.idReserva(),1,
                new DatosJornada.Destinatario("Persona Prueba","999111222"),new DatosJornada.Destino("Dirección sintética",null,null,null,"Prueba",null,null),
                LocalDate.of(2026,10,17),0,1,java.time.Instant.parse("2026-10-17T15:00:00Z"),2),"modulo-despacho");
        assertThat(sql.sql("SELECT id_pedido FROM proyeccion_despacho").query(String.class).single()).isEqualTo("PED-HEREDADO");
        assertThat(jornadas.consultarDetalle(jornada.idJornada()).ocupacion().pesoKg()).isEqualByComparingTo("10");
        assertThatThrownBy(() -> flota.cambiarEstado(vehiculo.idFurgoneta(),EstadoFurgoneta.EN_MANTENIMIENTO,actor))
                .isInstanceOf(com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota.class);
        jornadas.cerrar(jornada.idJornada(),actor,"GESTOR_DESPACHO");
        jornadas.liberar(reserva.idReserva(),despacho,"RECEPCION_EN_CENTRO","modulo-despacho");
        assertThat(jornadas.consultarDetalle(jornada.idJornada()).estado()).isEqualTo("CERRADA");
        assertThat(jornadas.consultarDetalle(jornada.idJornada()).ocupacion().pesoKg()).isEqualByComparingTo("0");
        assertThat(sql.sql("SELECT has_table_privilege('anon','proyecciones_despacho','SELECT')").query(Boolean.class).single()).isFalse();
    }
}
