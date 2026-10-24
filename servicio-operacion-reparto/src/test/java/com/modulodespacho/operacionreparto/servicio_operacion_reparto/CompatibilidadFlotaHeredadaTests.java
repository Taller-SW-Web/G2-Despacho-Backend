package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.nio.charset.StandardCharsets;
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
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.application.GestorFlota;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.DatosFlota.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "SEGURIDAD_URL=http://127.0.0.1:1")
@org.springframework.test.context.ActiveProfiles("render")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompatibilidadFlotaHeredadaTests {
    @Autowired DataSource origen;
    @Autowired JdbcClient sql;
    @Autowired GestorFlota gestor;

    @BeforeAll
    void prepararEsquemaYAplicarAdaptacionReal() throws Exception {
        try (var conexion = origen.getConnection(); var sentencia = conexion.createStatement();
                var recurso = getClass().getResourceAsStream("/flota/esquema-heredado.sql")) {
            sentencia.execute(new String(recurso.readAllBytes(), StandardCharsets.UTF_8));
            // La prueba ejecuta el mismo archivo operativo, sin una segunda copia del SQL.
            sentencia.execute(new ClassPathResource("flota/compatibilidad/F05__compatibilidad_operadores_flota.sql").getContentAsString(StandardCharsets.UTF_8));
        }
    }
    @Test
    void escribeSobreTablasOriginalesYCierraJornadaConAuditoria() {
        assertThat(sql.sql("SELECT has_table_privilege('anon', 'perfiles_flota', 'SELECT')")
                .query(Boolean.class).single()).isFalse();
        assertThat(sql.sql("SELECT has_function_privilege('authenticated', 'public.bloquear_operacion_flota()', 'EXECUTE')")
                .query(Boolean.class).single()).isFalse();
        UUID actor = UUID.randomUUID(), usuario = UUID.randomUUID();
        Repartidor repartidor = gestor.registrarRepartidor(new AltaRepartidor("Prueba", "Heredada", "12345678",
                new Contacto("999111222", "legado@example.invalid", "BRE-01", Turno.COMPLETO)), actor);
        gestor.vincular(repartidor.idRepartidor(), usuario, actor);
        assertThat(sql.sql("SELECT usuario_seguridad_id FROM repartidores").query(UUID.class).single()).isEqualTo(usuario);
        Furgoneta furgoneta = gestor.registrarFurgoneta("LEG-001", new Capacidad(
                new java.math.BigDecimal("100"), new java.math.BigDecimal("2"), 10), actor);
        UUID jornada = UUID.randomUUID();
        sql.sql("""
                INSERT INTO asignaciones_diarias (id,repartidor_id,furgoneta_id,zona_id_externa,fecha_jornada,
                    capacidad_max_kg,capacidad_max_m3,max_paquetes,estado,abierta_por)
                VALUES (:id,:repartidor,:furgoneta,:zona,CURRENT_DATE,100,2,10,'ACTIVA',:actor)
                """).param("id",jornada).param("repartidor",repartidor.idRepartidor()).param("furgoneta",furgoneta.idFurgoneta())
                .param("zona",UUID.randomUUID()).param("actor",actor).update();
        UUID reserva = UUID.randomUUID();
        sql.sql("""
                INSERT INTO reservas_capacidad (id,despacho_id_externo,asignacion_diaria_id,peso_kg,volumen_m3,cantidad_paquetes,estado)
                VALUES (:id,:despacho,:jornada,1,1,1,'ACTIVA')
                """).param("id",reserva).param("despacho",UUID.randomUUID()).param("jornada",jornada).update();
        assertThatThrownBy(() -> gestor.cambiarEstado(furgoneta.idFurgoneta(), EstadoFurgoneta.FUERA_DE_SERVICIO, actor))
                .isInstanceOf(com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain.ConflictoFlota.class);
        sql.sql("UPDATE reservas_capacidad SET estado='LIBERADA'").update();
        gestor.cambiarEstado(furgoneta.idFurgoneta(), EstadoFurgoneta.FUERA_DE_SERVICIO, actor);
        assertThat(sql.sql("SELECT estado FROM furgoneta").query(String.class).single()).isEqualTo("INACTIVA");
        assertThat(sql.sql("SELECT cerrada_en IS NOT NULL FROM asignaciones_diarias").query(Boolean.class).single()).isTrue();
        assertThat(gestor.listarFurgonetas(1,50,EstadoFurgoneta.FUERA_DE_SERVICIO,null).totalElementos()).isEqualTo(1);
        gestor.darDeBaja(repartidor.idRepartidor(), actor);
        assertThat(sql.sql("SELECT count(*) FROM auditoria_configuracion").query(Long.class).single()).isEqualTo(5);
    }
}
