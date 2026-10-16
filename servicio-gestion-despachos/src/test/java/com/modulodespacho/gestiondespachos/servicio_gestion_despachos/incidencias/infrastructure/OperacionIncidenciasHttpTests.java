package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Servidor HTTP simulado: no acredita la disponibilidad del proveedor de Operación. */
class OperacionIncidenciasHttpTests {

    private static final String URL = "http://operacion.prueba/internal/v1/operacion/consulta-incidencias";
    private static final Instant CONSULTADO_EN = Instant.parse("2026-10-16T14:00:00Z");
    private MockRestServiceServer servidor;
    private RestClient cliente;
    private OperacionIncidenciasHttp lector;

    @BeforeEach
    void preparar() {
        RestClient.Builder constructor = RestClient.builder().baseUrl("http://operacion.prueba");
        servidor = MockRestServiceServer.bindTo(constructor).build();
        cliente = constructor.build();
        lector = new OperacionIncidenciasHttp(cliente, () -> "credencial-tecnica-de-prueba");
    }

    @Test
    void noHaceHttpSinIdentificadoresOSinCredencialTecnica() {
        DatosOperativos vacios = lector.consultar(List.of(), List.of());
        DatosOperativos sinCredencial = new OperacionIncidenciasHttp(cliente, () -> "")
                .consultar(List.of("REP-12"), List.of("JOR-12"));

        assertThat(vacios.repartidores()).isEmpty();
        assertThat(vacios.jornadas()).isEmpty();
        assertThat(sinCredencial.repartidores().get("REP-12").nombre()).isNull();
        assertThat(sinCredencial.jornadas().get("JOR-12").estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
        servidor.verify();
    }

    @Test
    void deduplicaYDivideAmbosTiposEnLotesDeHastaCienSinPeticionesPorFila() {
        List<String> repartidores = IntStream.range(0, 101).mapToObj(i -> "REP-" + i).toList();
        List<String> jornadas = IntStream.range(0, 205).mapToObj(i -> "JOR-" + i).toList();
        List<String> jornadasRepetidas = new ArrayList<>(jornadas);
        jornadasRepetidas.addAll(jornadas);
        String respuesta = """
                {"repartidores":[],"jornadas":[],"consultadoEn":"2026-10-16T14:00:00Z"}
                """;
        for (int inicio = 0; inicio < jornadas.size(); inicio += 100) {
            List<String> loteRepartidores = inicio >= repartidores.size() ? List.of()
                    : repartidores.subList(inicio, Math.min(inicio + 100, repartidores.size()));
            List<String> loteJornadas = jornadas.subList(inicio, Math.min(inicio + 100, jornadas.size()));
            servidor.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("{\"idsRepartidor\":" + arregloJson(loteRepartidores)
                            + ",\"idsJornada\":" + arregloJson(loteJornadas) + "}"))
                    .andRespond(withSuccess(respuesta, MediaType.APPLICATION_JSON));
        }

        DatosOperativos resultado = lector.consultar(repartidores, jornadasRepetidas);

        assertThat(resultado.repartidores()).hasSize(101);
        assertThat(resultado.jornadas()).hasSize(205);
        assertThat(resultado.jornadas().values()).allSatisfy(jornada -> {
            assertThat(jornada.estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
            assertThat(jornada.consultadaEn()).isEqualTo(CONSULTADO_EN);
        });
        servidor.verify();
    }

    @Test
    void enviaCredencialTecnicaYConservaLosDatosYLaMarcaDeLaConsulta() {
        servidor.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer credencial-tecnica-de-prueba"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"idsRepartidor":["REP-12","REP-BAJA"],"idsJornada":["JOR-A","JOR-C","JOR-D"]}
                        """))
                .andRespond(withSuccess("""
                        {"repartidores":[{"idRepartidor":"REP-12","nombre":"Nombre de prueba"},
                                         {"idRepartidor":"REP-BAJA","nombre":null}],
                         "jornadas":[{"idJornada":"JOR-A","estado":"ACTIVA","cerradaEn":null},
                                     {"idJornada":"JOR-C","estado":"CERRADA","cerradaEn":"2026-10-15T23:00:00Z"},
                                     {"idJornada":"JOR-D","estado":"DESCONOCIDA","cerradaEn":null}],
                         "consultadoEn":"2026-10-16T14:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        DatosOperativos resultado = lector.consultar(List.of("REP-12", "REP-BAJA"), List.of("JOR-A", "JOR-C", "JOR-D"));

        assertThat(resultado.repartidores().get("REP-12").nombre()).isEqualTo("Nombre de prueba");
        assertThat(resultado.repartidores().get("REP-BAJA").nombre()).isNull();
        assertThat(resultado.jornadas().get("JOR-A").estado()).isEqualTo(EstadoJornada.ACTIVA);
        assertThat(resultado.jornadas().get("JOR-C").estado()).isEqualTo(EstadoJornada.CERRADA);
        assertThat(resultado.jornadas().get("JOR-C").cerradaEn()).isEqualTo(Instant.parse("2026-10-15T23:00:00Z"));
        assertThat(resultado.jornadas().get("JOR-D").estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
        assertThat(resultado.jornadas().values()).allSatisfy(jornada ->
                assertThat(jornada.consultadaEn()).isEqualTo(CONSULTADO_EN));
        servidor.verify();
    }

    @Test
    void omisionesDuplicadosYEstadosIncoherentesSoloDegradanLosElementosAfectados() {
        servidor.expect(requestTo(URL)).andRespond(withSuccess("""
                {"repartidores":[{"idRepartidor":"REP-12","nombre":"Primero"},
                                 {"idRepartidor":"REP-12","nombre":"Duplicado"},
                                 {"idRepartidor":"REP-AJENO","nombre":"No solicitado"}],
                 "jornadas":[{"idJornada":"JOR-DUP","estado":"CERRADA","cerradaEn":"2026-10-15T23:00:00Z"},
                             {"idJornada":"JOR-DUP","estado":"ACTIVA","cerradaEn":null},
                             {"idJornada":"JOR-SIN-FECHA","estado":"CERRADA","cerradaEn":null},
                             {"idJornada":"JOR-ACTIVA-MAL","estado":"ACTIVA","cerradaEn":"2026-10-15T23:00:00Z"},
                             {"idJornada":"JOR-ESTADO-MAL","estado":"OTRO","cerradaEn":null},
                             {"idJornada":"JOR-VALIDA","estado":"ACTIVA","cerradaEn":null},
                             {"idJornada":"JOR-AJENA","estado":"ACTIVA","cerradaEn":null}],
                 "consultadoEn":"2026-10-16T14:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        List<String> desconocidas = List.of("JOR-DUP", "JOR-SIN-FECHA", "JOR-ACTIVA-MAL", "JOR-ESTADO-MAL", "JOR-OMITIDA");
        List<String> jornadas = new ArrayList<>(desconocidas);
        jornadas.add("JOR-VALIDA");

        DatosOperativos resultado = lector.consultar(List.of("REP-12", "REP-OMITIDO"), jornadas);

        assertThat(resultado.repartidores()).containsOnlyKeys("REP-12", "REP-OMITIDO");
        assertThat(resultado.repartidores().values()).allSatisfy(repartidor -> assertThat(repartidor.nombre()).isNull());
        assertThat(resultado.jornadas()).hasSize(6).doesNotContainKey("JOR-AJENA");
        desconocidas.forEach(id -> {
            assertThat(resultado.jornadas().get(id).estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
            assertThat(resultado.jornadas().get(id).cerradaEn()).isNull();
        });
        assertThat(resultado.jornadas().get("JOR-VALIDA").estado()).isEqualTo(EstadoJornada.ACTIVA);
        servidor.verify();
    }

    @Test
    void timeoutORechazoDeCredencialNoReutilizanUnaJornadaConsultadaAntes() {
        servidor.expect(requestTo(URL)).andRespond(withSuccess("""
                {"repartidores":[],"jornadas":[{"idJornada":"JOR-12","estado":"ACTIVA","cerradaEn":null}],
                 "consultadoEn":"2026-10-16T14:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL)).andRespond(withException(new IOException("Timeout simulado")));
        servidor.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        servidor.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(lector.consultar(List.of(), List.of("JOR-12")).jornadas().get("JOR-12").estado())
                .isEqualTo(EstadoJornada.ACTIVA);
        for (int intento = 0; intento < 3; intento++) {
            DatosOperativos resultado = lector.consultar(List.of(), List.of("JOR-12"));
            assertThat(resultado.jornadas().get("JOR-12").estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
            assertThat(resultado.jornadas().get("JOR-12").consultadaEn()).isNull();
        }
        servidor.verify();
    }

    @Test
    void unLoteSinMarcaDeConsultaNoConfirmaDatosYNoDescartaLosOtrosLotes() {
        servidor.expect(requestTo(URL)).andRespond(withSuccess("""
                {"repartidores":[],"jornadas":[{"idJornada":"JOR-0","estado":"ACTIVA","cerradaEn":null}]}
                """, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL)).andRespond(withSuccess("""
                {"repartidores":[],"jornadas":[{"idJornada":"JOR-100","estado":"CERRADA", "cerradaEn":"2026-10-15T23:00:00Z"}],
                 "consultadoEn":"2026-10-16T14:00:00Z"}
                """, MediaType.APPLICATION_JSON));

        DatosOperativos resultado = lector.consultar(List.of(), IntStream.range(0, 101).mapToObj(i -> "JOR-" + i).toList());

        assertThat(resultado.jornadas().get("JOR-0").estado()).isEqualTo(EstadoJornada.DESCONOCIDA);
        assertThat(resultado.jornadas().get("JOR-0").consultadaEn()).isNull();
        assertThat(resultado.jornadas().get("JOR-100").estado()).isEqualTo(EstadoJornada.CERRADA);
        assertThat(resultado.jornadas().get("JOR-100").consultadaEn()).isEqualTo(CONSULTADO_EN);
        servidor.verify();
    }

    private static String arregloJson(List<String> ids) {
        return "[" + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()) + "]";
    }
}
