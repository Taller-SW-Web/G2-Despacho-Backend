package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** La consulta de zona usa una ruta de negocio de Gestión y debe llevar el secreto interno. */
class ConfiguracionJornadasTests {

    private final List<String> recibidas = new ArrayList<>();
    private HttpServer gestion;
    private UUID zona;

    @BeforeEach
    void iniciar() throws Exception {
        zona = UUID.randomUUID();
        gestion = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gestion.createContext(
                "/api/v1/zonas",
                solicitud -> {
                    synchronized (recibidas) {
                        recibidas.add(
                                String.valueOf(
                                        solicitud.getRequestHeaders().get("X-Despacho-Interno")));
                    }
                    byte[] cuerpo =
                            ("{\"id\":\"" + zona + "\",\"estado\":\"ACTIVO\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    solicitud.getResponseHeaders().set("Content-Type", "application/json");
                    solicitud.sendResponseHeaders(200, cuerpo.length);
                    try (var salida = solicitud.getResponseBody()) {
                        salida.write(cuerpo);
                    }
                });
        gestion.start();
    }

    @AfterEach
    void detener() {
        gestion.stop(0);
    }

    @Test
    void enviaElSecretoInternoAGestionCuandoEstaConfigurado() {
        consultar(" secreto-interno ");
        assertThat(recibidas).containsExactly("[secreto-interno]");
    }

    @Test
    void sinSecretoNoEnviaLaCabecera() {
        consultar("");
        consultar(null);
        assertThat(recibidas).containsExactly("null", "null");
    }

    private void consultar(String secreto) {
        new ConfiguracionJornadas()
                .consultaZona("http://127.0.0.1:" + gestion.getAddress().getPort(), secreto)
                .verificar(zona, "token-humano");
    }
}
