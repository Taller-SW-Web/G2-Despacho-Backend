package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.API;
import static com.modulodespacho.pruebas.integracion.soporte.EntornoCompose.GATEWAY;

import com.fasterxml.jackson.databind.JsonNode;
import com.modulodespacho.pruebas.integracion.soporte.ClienteApi;
import com.modulodespacho.pruebas.integracion.soporte.EntornoCompose;
import com.modulodespacho.pruebas.integracion.soporte.Informe;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * INT-02 e INT-08 del hito 5, migrados de verificar_f02_rutas.py y verificar_f02_publicado.py.
 * Gateway, Gestión, Operación y PostgreSQL son reales; Seguridad es el emisor temporal local.
 * Fuera de alcance: verificar_anulacion_fallido (F-04), que no respalda estos dos IDs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("F-02 rutas del repartidor (INT-02, INT-08)")
class RutasRepartidorF02Test {
    private static final String INT_02 = "INT-02";
    private static final String INT_08 = "INT-08";
    private static final Duration CONVERGENCIA_MAXIMA = Duration.ofSeconds(20);
    private static final double TOLERANCIA = 1e-8;

    private final Informe informe = new Informe("Hito 5 · INT-02 e INT-08 · rutas F-02 y F-03");
    private EntornoCompose entorno;
    private ClienteApi cliente;
    private EscenarioRutasF02 escenario;
    private boolean hayFallos;

    @RegisterExtension
    final TestWatcher vigia =
            new TestWatcher() {
                @Override
                public void testFailed(ExtensionContext contexto, Throwable causa) {
                    hayFallos = true;
                }
            };

    @BeforeAll
    void prepararEntorno() throws Exception {
        entorno = EntornoCompose.iniciar();
        cliente = entorno.cliente();
        try {
            escenario = EscenarioRutasF02.preparar(entorno);
            informe.dato("ids", escenario.identificadores());
            informe.dato(
                    "convergenciaProyeccionMs", esperarProyeccion().toMillis());
        } catch (Exception | AssertionError error) {
            hayFallos = true;
            throw error;
        }
    }

    @AfterAll
    void retirarEntorno() throws Exception {
        try {
            informe.escribir(entorno.informes(), "int-02-int-08.json");
            if (hayFallos) entorno.guardarRegistros();
        } finally {
            entorno.close();
        }
    }

    /** La asignación llega a Operación por proyección asíncrona; se espera con tiempo máximo. */
    private Duration esperarProyeccion() throws InterruptedException {
        Instant inicio = Instant.now();
        while (Duration.between(inicio, Instant.now()).compareTo(CONVERGENCIA_MAXIMA) < 0) {
            var propia = cliente.get(API + "/repartidor/mi-ruta", escenario.tokenA);
            var gestion = cliente.get(API + "/jornadas/" + escenario.jornadaA + "/ruta", escenario.tokenGestor);
            if (propia.estado() == 200
                    && gestion.estado() == 200
                    && apariciones(propia.json(), escenario.despacho) == 1
                    && apariciones(gestion.json(), escenario.despacho) == 1)
                return Duration.between(inicio, Instant.now());
            Thread.sleep(500);
        }
        throw new AssertionError(
                "La asignación no convergió en mi-ruta antes de " + CONVERGENCIA_MAXIMA.toSeconds() + " s");
    }

    @Nested
    @DisplayName("INT-02 · el despacho asignado aparece igual en cola, ruta administrativa y Mi Ruta")
    class Int02 {
        @Test
        @DisplayName("sin token, cola y Mi Ruta responden 401 y el Gateway está sano")
        void rechazaSinToken() {
            var salud = cliente.get(GATEWAY + "/actuator/health", null);
            informe.verificar(INT_02, "salud_gateway",
                    salud.estado() == 200 && salud.json() != null
                            && "UP".equals(salud.json().path("status").asText()),
                    "HTTP " + salud.estado());
            int cola = cliente.get(API + "/despachos/pendientes", null).estado();
            informe.verificar(INT_02, "cola_sin_token_401", cola == 401, "HTTP " + cola);
            int ruta = cliente.get(API + "/repartidor/mi-ruta", null).estado();
            informe.verificar(INT_02, "ruta_sin_token_401", ruta == 401, "HTTP " + ruta);
        }

        @Test
        @DisplayName("la cola autorizada tiene su estructura y ya no lista el despacho asignado")
        void colaAutorizada() {
            var respuesta = cliente.get(API + "/despachos/pendientes?pagina=1&tamanoPagina=100", escenario.tokenGestor);
            JsonNode cola = respuesta.json();
            informe.verificar(INT_02, "cola_autorizada_estructura",
                    respuesta.estado() == 200 && cola != null
                            && cola.path("contenido").isArray()
                            && cola.path("indicadores").isObject()
                            && !cola.path("fechaActualServidor").asText().isBlank(),
                    "HTTP " + respuesta.estado());
            // Aserción añadida respecto del verificador Python: un ASIGNADO sale de la cola.
            boolean presente = false;
            for (JsonNode fila : cola.path("contenido"))
                presente |= escenario.despacho.equals(fila.path("idDespacho").asText());
            informe.verificar(INT_02, "despacho_asignado_fuera_de_cola", !presente,
                    "El despacho ASIGNADO sigue en la cola de pendientes");
        }

        @Test
        @DisplayName("aparece una vez como ASIGNADO, con la misma jornada y secuencia en ambas rutas")
        void mismaJornadaYSecuencia() {
            JsonNode gestion = rutaGestion();
            JsonNode propia = cliente.get(API + "/repartidor/mi-ruta", escenario.tokenA).exigirJson(200);
            JsonNode jornada = detalleJornada();
            informe.verificar(INT_02, "ruta_gestion_estructura",
                    escenario.jornadaA.equals(gestion.path("idJornada").asText())
                            && "ACTIVA".equals(gestion.path("estadoJornada").asText())
                            && gestion.path("despachos").isArray()
                            && !gestion.path("versionRuta").asText().isBlank());
            informe.verificar(INT_02, "mi_ruta_jornada_propia",
                    escenario.jornadaA.equals(propia.path("idJornada").asText())
                            && propia.path("despachos").isArray(),
                    "idJornada=" + propia.path("idJornada").asText());

            List<JsonNode> enGestion = filas(gestion, escenario.despacho);
            List<JsonNode> enCampo = filas(propia, escenario.despacho);
            informe.verificar(INT_02, "aparece_una_vez_en_cada_ruta",
                    enGestion.size() == 1 && enCampo.size() == 1,
                    "gestión=" + enGestion.size() + ", mi-ruta=" + enCampo.size());
            JsonNode parada = enGestion.get(0);
            JsonNode propiaParada = enCampo.get(0);
            informe.verificar(INT_02, "estado_asignado_en_ambas",
                    "ASIGNADO".equals(parada.path("estado").asText())
                            && "ASIGNADO".equals(propiaParada.path("estado").asText()),
                    parada.path("estado").asText() + " / " + propiaParada.path("estado").asText());
            informe.verificar(INT_02, "misma_secuencia",
                    parada.path("posicion").isInt()
                            && parada.path("posicion").asInt() > 0
                            && parada.path("posicion").asInt() == propiaParada.path("secuenciaRuta").asInt(),
                    "posicion=" + parada.path("posicion") + ", secuenciaRuta=" + propiaParada.path("secuenciaRuta"));
            informe.verificar(INT_02, "mismo_repartidor_de_la_jornada",
                    parada.path("idRepartidor").asText().equals(jornada.path("idRepartidor").asText())
                            && escenario.perfilA.equals(jornada.path("idRepartidor").asText()));
        }

        @Test
        @DisplayName("ocupación, capacidad y remanente de la jornada son coherentes")
        void ocupacionCoherente() {
            JsonNode jornada = detalleJornada();
            JsonNode gestion = rutaGestion();
            informe.verificar(INT_02, "ocupacion_jornada_estructura",
                    escenario.jornadaA.equals(jornada.path("idJornada").asText())
                            && "ACTIVA".equals(jornada.path("estado").asText())
                            && dimensionesValidas(jornada));
            JsonNode parada = filas(gestion, escenario.despacho).get(0);
            Map<String, String> cargas = Map.of("pesoKg", "pesoKg", "volumenM3", "volumenM3", "paquetes", "cantidadPaquetes");
            for (var dimension : cargas.entrySet()) {
                double limite = jornada.path("limites").path(dimension.getKey()).asDouble();
                double usado = jornada.path("ocupacion").path(dimension.getKey()).asDouble();
                double libre = jornada.path("capacidadRemanente").path(dimension.getKey()).asDouble();
                double carga = parada.path(dimension.getValue()).asDouble();
                informe.verificar(INT_02, "capacidad_" + dimension.getKey(),
                        limite > 0 && usado >= carga && carga > 0 && Math.abs(limite - usado - libre) < TOLERANCIA,
                        "limite=" + limite + ", usado=" + usado + ", libre=" + libre + ", carga=" + carga);
                // Aserción añadida: la ocupación es exactamente la suma de la ruta de A.
                double suma = 0;
                for (JsonNode fila : gestion.path("despachos")) suma += fila.path(dimension.getValue()).asDouble();
                informe.verificar(INT_02, "ocupacion_igual_suma_ruta_" + dimension.getKey(),
                        Math.abs(usado - suma) < 1e-6, "usado=" + usado + ", sumaRuta=" + suma);
            }
        }
    }

    @Nested
    @DisplayName("INT-08 · el repartidor B no ve el detalle de un despacho de A")
    class Int08 {
        @Test
        @DisplayName("control: A ve su detalle con datos del destinatario mientras está ASIGNADO")
        void duenoVeSuDetalle() {
            JsonNode detalle = detallePropio(escenario.tokenA, escenario.despacho);
            informe.verificar(INT_08, "control_dueno_ve_datos_personales",
                    !detalle.path("destinatario").path("nombre").asText().isBlank()
                            && !detalle.path("destinatario").path("telefono").asText().isBlank()
                            && !detalle.path("destino").path("direccion").asText().isBlank());
        }

        @Test
        @DisplayName("control: el token de B es válido y ve su propio despacho")
        void bVeSuPropioDespacho() {
            var respuesta = cliente.get(API + "/repartidor/despachos/" + escenario.despachoB, escenario.tokenB);
            informe.verificar(INT_08, "control_b_ve_su_despacho",
                    respuesta.estado() == 200 && respuesta.json() != null
                            && escenario.despachoB.equals(respuesta.json().path("idDespacho").asText()),
                    "HTTP " + respuesta.estado());
        }

        @Test
        @DisplayName("B recibe 404 sin destinatario, dirección ni teléfono del despacho de A")
        void ajenoResponde404SinDetalle() {
            JsonNode detalleA = detallePropio(escenario.tokenA, escenario.despacho);
            // Valores sintéticos reales de este despacho, leídos por su dueño.
            List<String> sensibles = new ArrayList<>(List.of(
                    detalleA.path("destinatario").path("nombre").asText(),
                    detalleA.path("destinatario").path("telefono").asText(),
                    detalleA.path("destino").path("direccion").asText(),
                    detalleA.path("codigoRastreoInterno").asText()));
            sensibles.removeIf(String::isBlank);

            var ajeno = cliente.get(API + "/repartidor/despachos/" + escenario.despacho, escenario.tokenB);
            informe.verificar(INT_08, "despacho_ajeno_404", ajeno.estado() == 404, "HTTP " + ajeno.estado());
            for (String valor : sensibles)
                informe.verificar(INT_08, "cuerpo_sin_" + nombreDato(detalleA, valor),
                        !ajeno.texto().contains(valor), "El cuerpo del 404 revela un dato del despacho");
            JsonNode cuerpo = ajeno.json();
            boolean sinCamposDeDetalle = cuerpo == null || List.of("destinatario", "destino",
                    "secuenciaRuta", "idJornada", "hitos").stream().noneMatch(cuerpo::has);
            informe.verificar(INT_08, "cuerpo_sin_campos_de_detalle", sinCamposDeDetalle);
            if (cuerpo != null) informe.dato("codigoError404", cuerpo.path("code").asText(null));

            // No revela existencia: un ID inexistente responde igual que uno ajeno.
            var inexistente = cliente.get(API + "/repartidor/despachos/" + UUID.randomUUID(), escenario.tokenB);
            informe.verificar(INT_08, "ajeno_indistinguible_de_inexistente",
                    inexistente.estado() == 404
                            && (cuerpo == null || inexistente.json() == null
                                    || cuerpo.path("code").equals(inexistente.json().path("code"))),
                    "inexistente HTTP " + inexistente.estado());
        }

        private String nombreDato(JsonNode detalle, String valor) {
            if (valor.equals(detalle.path("destinatario").path("nombre").asText())) return "destinatario";
            if (valor.equals(detalle.path("destinatario").path("telefono").asText())) return "telefono";
            if (valor.equals(detalle.path("destino").path("direccion").asText())) return "direccion";
            return "codigo_rastreo";
        }
    }

    private JsonNode rutaGestion() {
        return cliente.get(API + "/jornadas/" + escenario.jornadaA + "/ruta", escenario.tokenGestor).exigirJson(200);
    }

    private JsonNode detalleJornada() {
        return cliente.get(API + "/jornadas/" + escenario.jornadaA, escenario.tokenGestor).exigirJson(200);
    }

    private JsonNode detallePropio(String token, String idDespacho) {
        return cliente.get(API + "/repartidor/despachos/" + idDespacho, token).exigirJson(200);
    }

    private static boolean dimensionesValidas(JsonNode jornada) {
        for (String campo : List.of("limites", "ocupacion", "capacidadRemanente"))
            for (String dimension : List.of("pesoKg", "volumenM3", "paquetes")) {
                JsonNode valor = jornada.path(campo).path(dimension);
                if (!valor.isNumber() || valor.asDouble() < 0) return false;
            }
        return true;
    }

    private static List<JsonNode> filas(JsonNode ruta, String idDespacho) {
        var encontradas = new ArrayList<JsonNode>();
        if (ruta != null)
            for (JsonNode fila : ruta.path("despachos"))
                if (idDespacho.equals(fila.path("idDespacho").asText())) encontradas.add(fila);
        return encontradas;
    }

    private static int apariciones(JsonNode ruta, String idDespacho) {
        return filas(ruta, idDespacho).size();
    }
}
