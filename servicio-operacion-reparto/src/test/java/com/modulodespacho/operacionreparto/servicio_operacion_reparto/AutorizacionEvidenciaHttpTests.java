package com.modulodespacho.operacionreparto.servicio_operacion_reparto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.operacionreparto.servicio_operacion_reparto.evidencias.application.AutorizadorCargaStorage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "SEGURIDAD_URL=http://127.0.0.1:1",
        "EVIDENCIAS_TIPOS_PERMITIDOS=image/jpeg",
        "EVIDENCIAS_MAXIMO_BYTES=2000000",
        "EVIDENCIAS_STORAGE_URL=https://proyecto.supabase.co",
        "EVIDENCIAS_CLAVE_SERVICIO=secreto-de-prueba",
        "EVIDENCIAS_BUCKET=evidencias"
})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AutorizacionEvidenciaHttpTests {
    private static final UUID USUARIO = UUID.fromString("4d498fc1-731e-49a5-a0a7-84a7d4df88dd");
    private static final UUID REPARTIDOR = UUID.fromString("86cfad0d-45c9-4a8d-969d-381dbddcf41c");
    private static final UUID FURGONETA = UUID.fromString("8a9ec898-500a-41e5-8fdb-d445f5c842e8");
    private static final UUID JORNADA = UUID.fromString("bdebc131-b029-4f8b-8424-43a8906ad0e3");
    private static final UUID DESPACHO = UUID.fromString("d15d4231-3343-44d6-b63f-1b9ce9f0c278");
    private static final String URL_FIRMADA =
            "https://proyecto.supabase.co/storage/v1/object/upload/sign/evidencias/objeto?token=firmado";

    @Autowired MockMvc http;
    @Autowired JdbcClient sql;
    @MockitoBean JwtDecoder decodificador;
    @MockitoBean AutorizadorCargaStorage storage;

    @BeforeEach
    void preparar() {
        sql.sql("""
                TRUNCATE evidencias, proyecciones_despacho, reservas_capacidad,
                    asignaciones_diarias, repartidores, furgonetas CASCADE
                """).update();
        sql.sql("""
                INSERT INTO repartidores (
                    id, nombres, apellidos, dni, telefono, correo, numero_brevete,
                    turno_habitual, estado_registro, id_usuario_seguridad, estado_vinculacion,
                    vinculado_en, creado_por, actualizado_por
                ) VALUES (
                    :id, 'Prueba', 'Repartidor', '12345678', '999111222', 'repartidor@example.invalid',
                    'BRE-123', 'COMPLETO', 'ACTIVO', :usuario, 'VINCULADO',
                    now(), :usuario, :usuario
                )
                """).param("id", REPARTIDOR).param("usuario", USUARIO).update();
        sql.sql("""
                INSERT INTO furgonetas (
                    id, placa, estado, capacidad_max_kg, capacidad_max_m3, max_paquetes,
                    creado_por, actualizado_por
                ) VALUES (:id, 'ABC-123', 'DISPONIBLE', 100, 10, 20, :usuario, :usuario)
                """).param("id", FURGONETA).param("usuario", USUARIO).update();
        sql.sql("""
                INSERT INTO asignaciones_diarias (
                    id, repartidor_id, furgoneta_id, zona_id_externa, fecha_jornada,
                    capacidad_max_kg, capacidad_max_m3, max_paquetes, estado, abierta_por
                ) VALUES (
                    :id, :repartidor, :furgoneta, :zona, CURRENT_DATE,
                    100, 10, 20, 'ACTIVA', :usuario
                )
                """).param("id", JORNADA).param("repartidor", REPARTIDOR).param("furgoneta", FURGONETA)
                .param("zona", UUID.randomUUID()).param("usuario", USUARIO).update();
        sql.sql("""
                INSERT INTO proyecciones_despacho (
                    despacho_id_externo, asignacion_diaria_id, estado, secuencia_ruta
                ) VALUES (:despacho, :jornada, 'EN_CAMINO', 1)
                """).param("despacho", DESPACHO).param("jornada", JORNADA).update();
        when(decodificador.decode("repartidor")).thenReturn(token(USUARIO));
        when(storage.autorizar(any(UUID.class), any(UUID.class))).thenReturn(new AutorizadorCargaStorage.AutorizacionStorage(
                URL_FIRMADA, Instant.now().plusSeconds(7200)));
    }

    @Test
    void autorizaUnaFotoPropiaYNoAlmacenaLaUrlFirmada() throws Exception {
        String cuerpo = """
                {"idDespacho":"%s","tipo":"ENTREGA","contentType":"image/jpeg","tamanioBytes":4096}
                """.formatted(DESPACHO);

        http.perform(post("/api/v1/repartidor/evidencias/autorizaciones")
                        .header("Authorization", "Bearer repartidor")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.idEvidencia").isString())
                .andExpect(jsonPath("$.urlCarga").value(URL_FIRMADA))
                .andExpect(jsonPath("$.venceEn").isString());

        verify(storage).autorizar(any(UUID.class), any(UUID.class));
        org.assertj.core.api.Assertions.assertThat(sql.sql("SELECT count(*) FROM evidencias WHERE despacho_id_externo = :id")
                .param("id", DESPACHO).query(Long.class).single()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sql.sql("SELECT count(*) FROM evidencias WHERE ruta_objeto LIKE '%token%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void rechazaFormatoNoPermitidoAntesDeSolicitarUrl() throws Exception {
        String cuerpo = """
                {"idDespacho":"%s","tipo":"ENTREGA","contentType":"image/png","tamanioBytes":4096}
                """.formatted(DESPACHO);

        http.perform(post("/api/v1/repartidor/evidencias/autorizaciones")
                        .header("Authorization", "Bearer repartidor")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_CAMPOS_INVALIDOS"));

        verifyNoInteractions(storage);
        org.assertj.core.api.Assertions.assertThat(sql.sql("SELECT count(*) FROM evidencias").query(Long.class).single()).isZero();
    }

    @Test
    void rechazaDespachoQueNoPerteneceAlRepartidorSinSolicitarUrl() throws Exception {
        String cuerpo = """
                {"idDespacho":"%s","tipo":"FALLO","contentType":"image/jpeg","tamanioBytes":4096}
                """.formatted(UUID.randomUUID());

        http.perform(post("/api/v1/repartidor/evidencias/autorizaciones")
                        .header("Authorization", "Bearer repartidor")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESPACHO_NO_ENCONTRADO"));

        verifyNoInteractions(storage);
    }

    @Test
    void exigeJwtHumanoConPerfilVinculado() throws Exception {
        String cuerpo = """
                {"idDespacho":"%s","tipo":"ENTREGA","contentType":"image/jpeg","tamanioBytes":4096}
                """.formatted(DESPACHO);

        http.perform(post("/api/v1/repartidor/evidencias/autorizaciones")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(storage);
    }

    private Jwt token(UUID subject) {
        Instant ahora = Instant.now();
        return Jwt.withTokenValue("repartidor").header("alg", "RS256").subject(subject.toString())
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(300))
                .claim("tipo", "acceso").claim("roles", List.of("REPARTIDOR")).build();
    }
}
