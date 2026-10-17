package com.modulodespacho.gestiondespachos.servicio_gestion_despachos;

import java.time.Instant;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.domain.Zona;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.zonas.application.RepositorioZonas;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "SEGURIDAD_URL=http://localhost:9999",
        "spring.flyway.enabled=true" })
@AutoConfigureMockMvc
class FlujoF01Tests {

    private static final UUID SAN_BORJA = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc cliente;

    @MockitoSpyBean
    RepositorioZonas zonas;

    @MockitoBean
    JwtDecoder decodificadorJwt;

    @BeforeEach
    void preparar() {
        when(decodificadorJwt.decode("gestor")).thenReturn(tokenGestor());
        when(decodificadorJwt.decode("servicio")).thenReturn(tokenServicio());
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
                {"nombre":"Lima Centro","distritos":[{"distrito":"San Borja","codigosPostales":["15037"]}],
                 "estado":"ACTIVO"}
                """;
        String respuesta = cliente.perform(post("/api/v1/zonas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.nombre").value("Lima Centro"))
                .andExpect(jsonPath("$.distritos[0].nombre").value("San Borja"))
                .andExpect(jsonPath("$.distritos[0].codigosPostales[0]").value("15037"))
                .andExpect(jsonPath("$.codigosPostales").doesNotExist())
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
        String zona = """
                {"nombre":"Lima Norte","distritos":[{"distrito":"Miraflores","codigosPostales":["15074"]}]}
                """;
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isCreated());

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(zona))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ZONA_CONFLICTO"));

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json")
                         .content("{\"nombre\":\"Otra zona\",\"distritos\":[{\"distrito\":\"Miraflores\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_ZONA_CONFLICTO"));

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("""
                                {"nombre":"CP solapado","distritos":[{"distrito":"Ate","codigosPostales":["15074"]}]}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void validaSolicitudesYDesactivacion() throws Exception {
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("{\"nombre\":\"Sin cobertura\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_CAMPOS_INVALIDOS"));

        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("""
                                {"nombre":"Distrito inválido","distritos":[{"distrito":"No existe"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_DISTRITO_NO_ENCONTRADO"));

        String zona = "{\"nombre\":\"Lima Sur\",\"distritos\":[{\"distrito\":\"Santiago de Surco\"}]}";
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
    void consultaCatalogoDeDistritos() throws Exception {
        cliente.perform(get("/api/v1/distritos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[?(@.nombre=='San Borja')]").exists())
                .andExpect(jsonPath("$[0].provincia").exists());

        cliente.perform(get("/api/v1/distritos").param("nombre", "comas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ubigeo").value("150110"));

        cliente.perform(get("/api/v1/distritos").param("nombre", "surco")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Santiago de Surco"));

        cliente.perform(get("/api/v1/distritos")).andExpect(status().isUnauthorized());

        cliente.perform(get("/api/v1/distritos").header(HttpHeaders.AUTHORIZATION, "Bearer servicio"))
                .andExpect(status().isForbidden());
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

    @Test
    void guardaConsultaYReemplazaCodigosAgrupadosPorDistrito() throws Exception {
        String respuesta = cliente.perform(post("/api/v1/zonas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("""
                                {"nombre":"Norte agrupado","distritos":[
                                  {"distrito":"Comas","codigosPostales":["15311","15312","15313","15314","15316",
                                    "15324","15326","15327","15328","15332"," 15311 "]},
                                  {"distrito":"Ancón","codigosPostales":["99999","99998"]}]}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(respuesta, "$.id");

        cliente.perform(get("/api/v1/zonas/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distritos.length()").value(2))
                .andExpect(jsonPath("$.distritos[0].nombre").value("Ancón"))
                .andExpect(jsonPath("$.distritos[0].codigosPostales.length()").value(2))
                .andExpect(jsonPath("$.distritos[1].nombre").value("Comas"))
                .andExpect(jsonPath("$.distritos[1].codigosPostales.length()").value(10));

        cliente.perform(get("/api/v1/zonas").param("distrito", "Comas")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].distritos[1].codigosPostales.length()").value(10));

        // Distrito sin cobertura propia: obliga a resolver usando el CP de la tabla nueva.
        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json")
                        .content("{\"destino\":{\"distrito\":\"Carabayllo\",\"codigoPostal\":\"15311\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreZona").value("Norte agrupado"));

        cliente.perform(put("/api/v1/zonas/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content("""
                                {"nombre":"Norte editado","distritos":[
                                  {"distrito":"Comas","codigosPostales":["15332"]}]}
                                """))
                .andExpect(status().isOk());

        cliente.perform(get("/api/v1/zonas/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer gestor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distritos.length()").value(1))
                .andExpect(jsonPath("$.distritos[0].codigosPostales.length()").value(1))
                .andExpect(jsonPath("$.distritos[0].codigosPostales[0]").value("15332"));

        cliente.perform(post("/api/v1/cotizaciones").header(HttpHeaders.AUTHORIZATION, "Bearer servicio")
                        .contentType("application/json")
                        .content("{\"destino\":{\"distrito\":\"Carabayllo\",\"codigoPostal\":\"15311\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coberturaDisponible").value(false));
    }

    @Test
    void erroresDePersistenciaNoSeConfundenConPermisos() throws Exception {
        String cuerpo = "{\"nombre\":\"Fallo BD\",\"distritos\":[{\"distrito\":\"La Punta\"}]}";
        doThrow(new DataIntegrityViolationException("Detalle SQL interno"))
                .when(zonas).guardar(any(Zona.class));
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_INTERNO"))
                .andExpect(jsonPath("$.detail").value("No fue posible guardar la operación"));

        doThrow(new DataIntegrityViolationException("Duplicado", new SQLException("SQL interno", "23505")))
                .when(zonas).guardar(any(Zona.class));
        cliente.perform(post("/api/v1/zonas").header(HttpHeaders.AUTHORIZATION, "Bearer gestor")
                        .contentType("application/json").content(cuerpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESP_ERROR_CONFLICTO_PERSISTENCIA"));

        cliente.perform(get("/error").with(solicitud -> {
                    solicitud.setDispatcherType(DispatcherType.ERROR);
                    return solicitud;
                }))
                .andExpect(status().isInternalServerError());
    }
}
