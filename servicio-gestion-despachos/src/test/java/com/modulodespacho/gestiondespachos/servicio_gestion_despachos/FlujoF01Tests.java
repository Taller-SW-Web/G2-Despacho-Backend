package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Distrito;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.infrastructure.persistence.DistritoRepositorioJpa;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "SEGURIDAD_URL=http://localhost:9999")
@AutoConfigureMockMvc
class FlujoF01Tests {

    private static final UUID SAN_BORJA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MIRAFLORES = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SURCO = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    MockMvc cliente;

    @Autowired
    DistritoRepositorioJpa distritos;

    @MockitoBean
    JwtDecoder decodificadorJwt;

    @BeforeEach
    void preparar() {
        sembrarDistrito(SAN_BORJA, "San Borja");
        sembrarDistrito(MIRAFLORES, "Miraflores");
        sembrarDistrito(SURCO, "Santiago de Surco");
        when(decodificadorJwt.decode("gestor")).thenReturn(tokenGestor());
        when(decodificadorJwt.decode("servicio")).thenReturn(tokenServicio());
    }

    private void sembrarDistrito(UUID id, String nombre) {
        if (distritos.findById(id).isEmpty()) {
            Distrito distrito = new Distrito();
            distrito.setId(id);
            distrito.setNombre(nombre);
            distrito.setProvincia("Lima");
            distrito.setDepartamento("Lima");
            distritos.save(distrito);
        }
    }

    private Jwt tokenGestor() {
        return Jwt.withTokenValue("gestor").header("alg", "RS256").subject(SAN_BORJA.toString())
                .claim("tipo", "acceso").claim("roles", List.of("GESTOR_DESPACHO"))
                .audience(List.of("api-despacho")).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private Jwt tokenServicio() {
        return Jwt.withTokenValue("servicio").header("alg", "RS256").subject("modulo-marketplace")
                .claim("tipo", "servicio").claim("scope", "cotizaciones:calcular")
                .audience(List.of("api-despacho")).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    void flujoCompletoCreacionTarifaYCotizacion() throws Exception {
        String zona = """
                {"nombre":"Lima Centro","distritos":["San Borja"],"codigosPostales":["15037"],
                 "estado":"ACTIVO"}
                """;
        String respuesta = cliente.perform(post("/api/v1/zonas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.nombre").value("Lima Centro"))
                .andExpect(jsonPath("$.distritos[0].nombre").value("San Borja"))
                .andExpect(jsonPath("$.codigosPostales[0]").value("15037"))
                .andReturn().getResponse().getContentAsString();
        String idZona = JsonPath.read(respuesta, "$.id");

        String tarifa = """
                {"tarifaBase":10,"pesoIncluidoKg":3,"recargoKgAdicional":2,
                 "factorVolumetrico":1.25,"moneda":"PEN","plazoEstimadoDiasHabiles":2}
                """;
        cliente.perform(put("/api/v1/zonas/{id}/tarifa", idZona)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(tarifa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tarifaBase").value(10.0))
                .andExpect(jsonPath("$.moneda").value("PEN"));

        cliente.perform(get("/api/v1/zonas/{id}/tarifa", idZona)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pesoIncluidoKg").value(3.0));

        String cotizacion = """
                {"destino":{"distrito":"San Borja"},
                 "lineas":[{"sku":"POL-NEG-M","cantidad":5},{"sku":"ZAP-RUN-42","cantidad":1}]}
                """;
        cliente.perform(post("/api/v1/cotizaciones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json").content(cotizacion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoCotizacion").value("EXACTA"))
                .andExpect(jsonPath("$.nombreZona").value("Lima Centro"))
                .andExpect(jsonPath("$.pesoTotalKg").value(2.65))
                .andExpect(jsonPath("$.volumenTotalM3").value(0.021))
                .andExpect(jsonPath("$.costoEnvio").value(10.0))
                .andExpect(jsonPath("$.plazoEstimadoDiasHabiles").value(2))
                .andExpect(jsonPath("$.fechaEstimadaEntrega").exists());

        cliente.perform(post("/api/v1/cotizaciones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json").content("{\"destino\":{\"distrito\":\"San Borja\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(true))
                .andExpect(jsonPath("$.nombreZona").value("Lima Centro"))
                .andExpect(jsonPath("$.costoEnvio").doesNotExist());
    }

    @Test
    void rechazaDuplicadoYSolapamiento() throws Exception {
        String zona = "{\"nombre\":\"Lima Norte\",\"distritos\":[\"Miraflores\"]}";
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isCreated());

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ZONA_CONFLICTO"));

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json")
                        .content("{\"nombre\":\"Otra zona\",\"distritos\":[\"Miraflores\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ZONA_CONFLICTO"));
    }

    @Test
    void validaSolicitudesYDesactivacion() throws Exception {
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("{\"nombre\":\"Sin cobertura\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_CAMPOS_INVALIDOS"));

        String zona = "{\"nombre\":\"Lima Sur\",\"distritos\":[\"Santiago de Surco\"]}";
        String respuesta = cliente.perform(post("/api/v1/zonas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idZona = JsonPath.read(respuesta, "$.id");

        cliente.perform(patch("/api/v1/zonas/{id}/estado", idZona)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("{\"estado\":\"INACTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("INACTIVO"));

        cliente.perform(patch("/api/v1/zonas/{id}/estado", idZona)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("{\"estado\":\"INACTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("INACTIVO"));

        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json")
                        .content("{\"destino\":{\"distrito\":\"Santiago de Surco\"},\"lineas\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(false));

        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DESTINO_REQUERIDO"));
    }

    @Test
    void exigeAutorizacionAdministrativa() throws Exception {
        cliente.perform(get("/api/v1/zonas")).andExpect(status().isUnauthorized());

        cliente.perform(get("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer servicio"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_SCOPE_INSUFICIENTE"));

        cliente.perform(post("/api/v1/cotizaciones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("{\"destino\":{\"distrito\":\"San Borja\"}}"))
                .andExpect(status().isForbidden());
    }
}
