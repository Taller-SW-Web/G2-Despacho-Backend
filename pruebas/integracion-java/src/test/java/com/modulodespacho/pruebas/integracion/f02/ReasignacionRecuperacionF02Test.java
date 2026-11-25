package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.API;

import com.fasterxml.jackson.databind.JsonNode;
import com.modulodespacho.pruebas.integracion.soporte.ClienteApi;
import com.modulodespacho.pruebas.integracion.soporte.EntornoCompose;
import com.modulodespacho.pruebas.integracion.soporte.Informe;
import com.modulodespacho.pruebas.integracion.soporte.IntermediarioCorte;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * INT-09 del hito 5: reasignación por Gateway con Gestión y Operación reales. Un intermediario
 * de prueba deja que Operación confirme la transferencia y corta su primera respuesta hacia
 * Gestión. El reintento idéntico debe recuperar la operación sin duplicar reservas.
 *
 * <p>Las regresiones aisladas siguen en ReasignacionHttpTests (Gestión) y JornadasPostgresTests
 * (Operación); este caso ejecuta juntas ambas implementaciones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("F-02 reasignación con respuesta perdida (INT-09)")
class ReasignacionRecuperacionF02Test {
    private static final String INT_09 = "INT-09";
    private static final String OPERACION_PUBLICADA = "http://127.0.0.1:18082";
    private static final Duration CONVERGENCIA_MAXIMA = Duration.ofSeconds(20);
    private static final String MOTIVO = "Prueba INT-09: redistribución de carga";

    private final Informe informe = new Informe("Hito 5 · INT-09 · reasignación y recuperación");
    private IntermediarioCorte intermediario;
    private EntornoCompose entorno;
    private ClienteApi cliente;
    private EscenarioRutasF02 escenario;

    @BeforeAll
    void prepararEntorno() throws Exception {
        intermediario = IntermediarioCorte.iniciar(OPERACION_PUBLICADA);
        entorno = EntornoCompose.iniciar(List.of(superposicion(intermediario.puerto())));
        cliente = entorno.cliente();
        escenario = EscenarioRutasF02.preparar(entorno);
        informe.dato("ids", escenario.identificadores());
        esperarHasta(() -> rutaPropia(escenario.tokenA).contains(escenario.despacho),
                "la asignación inicial no llegó a Mi Ruta de A");
    }

    @AfterAll
    void retirarEntorno() throws Exception {
        try {
            informe.dato("transferenciasVistasPorIntermediario", eventos());
            informe.escribir(entorno.informes(), "int-09.json");
            entorno.guardarRegistros();
        } finally {
            entorno.close();
            intermediario.close();
        }
    }

    @Test
    @DisplayName("el reintento recupera la transferencia confirmada cuya respuesta se perdió")
    void recuperaTrasRespuestaPerdida() throws Exception {
        String despacho = escenario.despacho;
        JsonNode parada = paradaEnGestion(escenario.jornadaA, despacho);
        int version = parada.path("version").asInt();
        var antes = lectura();
        List<String> reservasAntes = reservas(despacho);
        informe.dato("antes", antes);
        informe.dato("reservasAntes", reservasAntes);
        informe.verificar(INT_09, "inicio_una_reserva_activa_en_a",
                reservasAntes.size() == 1 && reservasAntes.get(0).endsWith("|" + escenario.jornadaA + "|ACTIVA"),
                String.valueOf(reservasAntes));
        String reservaOriginal = reservasAntes.get(0).split("\\|")[0];

        // 1. Solicitud con corte armado en el intermediario.
        String clave = UUID.randomUUID().toString();
        Map<String, Object> cuerpo = Map.of(
                "idNuevoRepartidor", escenario.perfilB,
                "idNuevaJornada", escenario.jornadaB,
                "motivo", MOTIVO);
        intermediario.armarCorte();
        var primera = reasignar(despacho, cuerpo, clave, version);
        informe.dato("primeraRespuesta", Map.of("http", primera.estado(), "code", codigo(primera)));
        // Contrato de Gestión §3.2.2: un 503 no demuestra reversión y obliga a recuperar con la
        // misma solicitud. El código depende de dónde se detecte la falla de Operación.
        informe.verificar(INT_09, "primera_respuesta_503_recuperable",
                primera.estado() == 503
                        && List.of("DESP_ERROR_OPERACION_NO_DISPONIBLE", "DESP_ERROR_REASIGNACION_NO_DISPONIBLE")
                                .contains(codigo(primera)),
                "HTTP " + primera.estado() + " " + codigo(primera));

        // 2. El corte ocurrió después de que Operación confirmara la transferencia.
        var eventos = intermediario.eventos();
        informe.verificar(INT_09, "solo_la_primera_transferencia_cortada",
                !eventos.isEmpty() && eventos.get(0).cortada()
                        && eventos.stream().skip(1).noneMatch(IntermediarioCorte.Evento::cortada),
                "eventos=" + eventos.size());
        var corte = eventos.get(0);
        informe.verificar(INT_09, "operacion_confirmo_antes_del_corte",
                corte.estadoOperacion() == 200
                        && "TRANSFERIDA".equals(corte.estadoTransferencia())
                        && corte.idNuevaReserva() != null
                        && !corte.respuestaOperacion().isAfter(corte.corte()),
                corte.toString());
        List<String> reservasTrasCorte = reservas(despacho);
        informe.dato("reservasTrasCorte", reservasTrasCorte);
        informe.verificar(INT_09, "transferencia_durable_en_operacion_tras_corte",
                reservasTrasCorte.contains(reservaOriginal + "|" + escenario.jornadaA + "|LIBERADA")
                        && reservasTrasCorte.contains(corte.idNuevaReserva() + "|" + escenario.jornadaB + "|ACTIVA"),
                String.valueOf(reservasTrasCorte));

        // 3. Reintento idéntico: misma identidad, clave, versión y cuerpo.
        int transferenciasAntesDelReintento = intermediario.eventos().size();
        var segunda = reasignar(despacho, cuerpo, clave, version);
        int transferenciasDelReintento = intermediario.eventos().size() - transferenciasAntesDelReintento;
        informe.dato("recuperacion", Map.of(
                "transferenciasReenviadasPorCicloPeriodicoAntesDelReintento", transferenciasAntesDelReintento - 1,
                "transferenciasReenviadasDuranteElReintento", transferenciasDelReintento,
                // Solo se mide cuándo ocurrió el reenvío, no qué hilo de Gestión lo hizo.
                "reenvioObservado", transferenciasAntesDelReintento > 1
                        ? "antes del reintento (ciclo periódico de Gestión)"
                        : "durante el reintento del cliente"));
        informe.verificar(INT_09, "reenvios_devuelven_la_misma_transferencia",
                intermediario.eventos().stream().skip(1).allMatch(e -> e.estadoOperacion() == 200
                        && corte.idNuevaReserva().equals(e.idNuevaReserva())),
                intermediario.eventos().toString());
        JsonNode resultado = segunda.json();
        informe.verificar(INT_09, "reintento_200", segunda.estado() == 200 && resultado != null,
                "HTTP " + segunda.estado() + " " + codigo(segunda));
        informe.verificar(INT_09, "reintento_devuelve_la_reasignacion",
                despacho.equals(resultado.path("idDespacho").asText())
                        && "ASIGNADO".equals(resultado.path("estado").asText())
                        && escenario.perfilA.equals(resultado.path("idRepartidorAnterior").asText())
                        && escenario.jornadaA.equals(resultado.path("idJornadaAnterior").asText())
                        && escenario.perfilB.equals(resultado.path("idRepartidor").asText())
                        && escenario.jornadaB.equals(resultado.path("idJornada").asText())
                        && MOTIVO.equals(resultado.path("motivo").asText()),
                resultado.toString());
        informe.verificar(INT_09, "reintento_usa_la_reserva_confirmada",
                corte.idNuevaReserva().equals(resultado.path("idReserva").asText()),
                "idReserva=" + resultado.path("idReserva").asText());
        var tercera = reasignar(despacho, cuerpo, clave, version);
        informe.verificar(INT_09, "repeticion_devuelve_la_misma_respuesta",
                tercera.estado() == 200 && resultado.equals(tercera.json()), "HTTP " + tercera.estado());

        // 4. Reservas: la de A liberada y una sola activa, en B, para ese despacho.
        List<String> reservasDespues = reservas(despacho);
        informe.dato("reservasDespues", reservasDespues);
        long activas = reservasDespues.stream().filter(fila -> fila.endsWith("|ACTIVA")).count();
        informe.verificar(INT_09, "reserva_original_liberada",
                reservasDespues.contains(reservaOriginal + "|" + escenario.jornadaA + "|LIBERADA"),
                String.valueOf(reservasDespues));
        informe.verificar(INT_09, "una_sola_reserva_activa_en_b",
                activas == 1 && reservasDespues.contains(corte.idNuevaReserva() + "|" + escenario.jornadaB + "|ACTIVA")
                        && reservasDespues.size() == 2,
                String.valueOf(reservasDespues));
        var claves = intermediario.eventos().stream().map(IntermediarioCorte.Evento::claveIdempotencia).distinct().toList();
        List<String> transferencias = entorno.consultar("despacho_operacion",
                "SELECT count(*) FROM transferencias_capacidad WHERE clave='" + claves.get(0) + "';");
        informe.verificar(INT_09, "operacion_guarda_una_transferencia_por_clave",
                claves.size() == 1 && List.of("1").equals(transferencias),
                "claves=" + claves.size() + ", filas=" + transferencias);

        // 5. Rutas: el despacho solo en las de B al converger la proyección.
        esperarHasta(() -> !rutaPropia(escenario.tokenA).contains(despacho)
                        && rutaPropia(escenario.tokenB).contains(despacho),
                "Mi Ruta no convergió tras la reasignación");
        var despues = lectura();
        informe.dato("despues", despues);
        informe.verificar(INT_09, "solo_en_rutas_de_b",
                veces(despues.get("rutaGestionA"), despacho) == 0
                        && veces(despues.get("miRutaA"), despacho) == 0
                        && veces(despues.get("rutaGestionB"), despacho) == 1
                        && veces(despues.get("miRutaB"), despacho) == 1,
                String.valueOf(despues));

        // 6. Capacidad: A recupera la carga del despacho y B la asume.
        for (var dimension : Map.of("pesoKg", "pesoKg", "volumenM3", "volumenM3", "paquetes", "cantidadPaquetes").entrySet()) {
            double carga = parada.path(dimension.getValue()).asDouble();
            double aAntes = ocupacion(antes, "ocupacionA", dimension.getKey());
            double aDespues = ocupacion(despues, "ocupacionA", dimension.getKey());
            double bAntes = ocupacion(antes, "ocupacionB", dimension.getKey());
            double bDespues = ocupacion(despues, "ocupacionB", dimension.getKey());
            informe.verificar(INT_09, "capacidad_transferida_" + dimension.getKey(),
                    Math.abs(aAntes - carga - aDespues) < 1e-6 && Math.abs(bAntes + carga - bDespues) < 1e-6,
                    "A " + aAntes + "→" + aDespues + ", B " + bAntes + "→" + bDespues + ", carga " + carga);
        }
    }

    private ClienteApi.Respuesta reasignar(String despacho, Map<String, Object> cuerpo, String clave, int version) {
        return cliente.postJsonConCabeceras(
                API + "/despachos/" + despacho + "/reasignacion", cuerpo, escenario.tokenGestor,
                Map.of("Idempotency-Key", clave, "If-Match", "\"" + version + "\""));
    }

    /** Rutas y ocupación de ambas jornadas, solo con IDs y cifras. */
    private Map<String, Object> lectura() {
        var lectura = new LinkedHashMap<String, Object>();
        lectura.put("rutaGestionA", idsEnGestion(escenario.jornadaA));
        lectura.put("rutaGestionB", idsEnGestion(escenario.jornadaB));
        lectura.put("miRutaA", rutaPropia(escenario.tokenA));
        lectura.put("miRutaB", rutaPropia(escenario.tokenB));
        lectura.put("ocupacionA", ocupacionJornada(escenario.jornadaA));
        lectura.put("ocupacionB", ocupacionJornada(escenario.jornadaB));
        return lectura;
    }

    private List<String> idsEnGestion(String jornada) {
        var ids = new ArrayList<String>();
        for (JsonNode fila : cliente.get(API + "/jornadas/" + jornada + "/ruta", escenario.tokenGestor)
                .exigirJson(200).path("despachos"))
            ids.add(fila.path("idDespacho").asText());
        return ids;
    }

    private List<String> rutaPropia(String token) {
        var ids = new ArrayList<String>();
        for (JsonNode fila : cliente.get(API + "/repartidor/mi-ruta", token).exigirJson(200).path("despachos"))
            ids.add(fila.path("idDespacho").asText());
        return ids;
    }

    private Map<String, Double> ocupacionJornada(String jornada) {
        JsonNode ocupacion = cliente.get(API + "/jornadas/" + jornada, escenario.tokenGestor)
                .exigirJson(200).path("ocupacion");
        return Map.of(
                "pesoKg", ocupacion.path("pesoKg").asDouble(),
                "volumenM3", ocupacion.path("volumenM3").asDouble(),
                "paquetes", ocupacion.path("paquetes").asDouble());
    }

    private JsonNode paradaEnGestion(String jornada, String despacho) {
        for (JsonNode fila : cliente.get(API + "/jornadas/" + jornada + "/ruta", escenario.tokenGestor)
                .exigirJson(200).path("despachos"))
            if (despacho.equals(fila.path("idDespacho").asText())) return fila;
        throw new AssertionError("El despacho no está en la ruta de la jornada " + jornada);
    }

    /** Filas id|jornada|estado de las reservas del despacho en Operación, por creación. */
    private List<String> reservas(String despacho) throws Exception {
        return entorno.consultar("despacho_operacion",
                "SELECT id, asignacion_diaria_id, estado FROM reservas_capacidad WHERE despacho_id_externo='"
                        + despacho + "' ORDER BY creado_en;");
    }

    private List<Map<String, Object>> eventos() {
        var lista = new ArrayList<Map<String, Object>>();
        for (var evento : intermediario.eventos()) {
            var fila = new LinkedHashMap<String, Object>();
            fila.put("numero", evento.numero());
            fila.put("recibida", evento.recibida().toString());
            fila.put("estadoOperacion", evento.estadoOperacion());
            fila.put("estadoTransferencia", evento.estadoTransferencia());
            fila.put("idNuevaReserva", evento.idNuevaReserva());
            fila.put("respuestaOperacion", evento.respuestaOperacion().toString());
            fila.put("cortada", evento.cortada());
            fila.put("corte", evento.corte() == null ? null : evento.corte().toString());
            lista.add(fila);
        }
        return lista;
    }

    @SuppressWarnings("unchecked")
    private static int veces(Object ids, String despacho) {
        return (int) ((List<String>) ids).stream().filter(despacho::equals).count();
    }

    @SuppressWarnings("unchecked")
    private static double ocupacion(Map<String, Object> lectura, String jornada, String dimension) {
        return ((Map<String, Double>) lectura.get(jornada)).get(dimension);
    }

    private static String codigo(ClienteApi.Respuesta respuesta) {
        return respuesta.json() == null ? null : respuesta.json().path("code").asText(null);
    }

    private static void esperarHasta(java.util.function.BooleanSupplier condicion, String mensaje)
            throws InterruptedException {
        Instant limite = Instant.now().plus(CONVERGENCIA_MAXIMA);
        while (Instant.now().isBefore(limite)) {
            if (condicion.getAsBoolean()) return;
            Thread.sleep(500);
        }
        throw new AssertionError(mensaje + " en " + CONVERGENCIA_MAXIMA.toSeconds() + " s");
    }

    /**
     * Superposición Compose del caso: Gestión llama a Operación a través del intermediario del
     * anfitrión; Operación se publica en loopback para que el intermediario la alcance. La
     * recuperación periódica de Gestión (500 ms) se conserva porque también entrega los eventos
     * de proyección; por eso la transferencia pendiente puede completarla ese ciclo o el
     * reintento del cliente, y el informe registra cuál.
     */
    private static Path superposicion(int puerto) throws Exception {
        Path archivo = Files.createTempFile("hito5-int09-", ".yaml");
        archivo.toFile().deleteOnExit();
        Files.writeString(archivo, """
                services:
                  gestion:
                    environment:
                      OPERACION_REPARTO_URL: http://host.docker.internal:%d
                    extra_hosts: ['host.docker.internal:host-gateway']
                  operacion:
                    ports: ['127.0.0.1:18082:8082']
                """.formatted(puerto));
        return archivo;
    }
}
