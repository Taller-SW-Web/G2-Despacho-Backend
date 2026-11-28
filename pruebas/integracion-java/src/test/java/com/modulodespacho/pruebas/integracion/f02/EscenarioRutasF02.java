package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.soporte.EntornoCompose.GATEWAY;
import static com.modulodespacho.pruebas.integracion.soporte.EntornoCompose.SEGURIDAD;

import com.fasterxml.jackson.databind.JsonNode;
import com.modulodespacho.pruebas.integracion.soporte.ClienteApi;
import com.modulodespacho.pruebas.integracion.soporte.EntornoCompose;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Datos sintéticos migrados de verificar_f02_rutas.py (líneas 130-170): una zona con tarifa, el
 * repartidor A (perfil "repartidor" de Seguridad local) y el repartidor B (perfil "sin-rol"),
 * ambos vinculados y con jornada activa hoy. A recibe dos despachos y B uno.
 */
public final class EscenarioRutasF02 {
    static final String API = GATEWAY + "/api/v1";
    static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String USUARIO_A = "22222222-2222-4222-8222-222222222222";
    private static final String USUARIO_B = "33333333-3333-4333-8333-333333333333";

    final String tokenGestor;
    final String tokenA;
    final String tokenB;
    final String zona = UUID.randomUUID().toString();
    final String distrito = UUID.randomUUID().toString();
    final String perfilA = UUID.randomUUID().toString();
    final String perfilB = UUID.randomUUID().toString();
    final String furgonetaA = UUID.randomUUID().toString();
    final String furgonetaB = UUID.randomUUID().toString();
    final String jornadaA;
    final String jornadaB;
    /** Despacho bajo prueba: el último asignado a A, como en el verificador Python. */
    final String despacho;
    final String otroDespachoA;
    final String despachoB;

    private EscenarioRutasF02(EntornoCompose entorno) throws Exception {
        var cliente = entorno.cliente();
        tokenGestor = tokenUsuario(cliente, "gestor");
        tokenA = tokenUsuario(cliente, "repartidor");
        // F-03 habilita por perfil vinculado, no por un rol global de repartidor.
        tokenB = tokenUsuario(cliente, "sin-rol");

        entorno.sql(
                "despacho_gestion",
                """
                INSERT INTO distrito(id,nombre) VALUES ('%1$s','Distrito de pruebas F02');
                INSERT INTO zonas(id,nombre,estado) VALUES ('%2$s','Zona de pruebas F02','ACTIVO');
                INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('%2$s','%1$s');
                INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('%2$s',10,1);
                """
                        .formatted(distrito, zona));
        entorno.sql(
                "despacho_operacion",
                repartidor(perfilA, "Ruta", "12345678", "999111222", "ruta", "TEST-01", USUARIO_A)
                        + furgoneta(furgonetaA, "F02-TEST")
                        + repartidor(
                                perfilB, "Destino", "87654321", "999111223", "destino", "TEST-02",
                                USUARIO_B)
                        + furgoneta(furgonetaB, "F02-DEST"));

        String hoy = LocalDate.now(ZoneId.of("America/Lima")).toString();
        jornadaA = crearJornada(cliente, perfilA, furgonetaA, hoy);
        jornadaB = crearJornada(cliente, perfilB, furgonetaB, hoy);
        despachoB = crearYAsignar(cliente, perfilB, jornadaB);
        otroDespachoA = crearYAsignar(cliente, perfilA, jornadaA);
        despacho = crearYAsignar(cliente, perfilA, jornadaA);
    }

    static EscenarioRutasF02 preparar(EntornoCompose entorno) throws Exception {
        return new EscenarioRutasF02(entorno);
    }

    Map<String, Object> identificadores() {
        return Map.of(
                "idZona", zona,
                "idRepartidorA", perfilA,
                "idRepartidorB", perfilB,
                "idJornadaA", jornadaA,
                "idJornadaB", jornadaB,
                "idDespachoBajoPrueba", despacho,
                "otroDespachoA", otroDespachoA,
                "despachoPropioB", despachoB);
    }

    private String crearJornada(ClienteApi cliente, String perfil, String furgoneta, String fecha) {
        var respuesta =
                cliente.postJson(
                        API + "/jornadas",
                        Map.of(
                                "idRepartidor", perfil,
                                "idFurgoneta", furgoneta,
                                "idZona", zona,
                                "fecha", fecha),
                        tokenGestor,
                        null);
        return exigirExito(respuesta, "crear jornada").path("idJornada").asText();
    }

    private String crearYAsignar(ClienteApi cliente, String perfil, String jornada) {
        var simulado =
                cliente.postJson(
                        API + "/despachos/simulaciones",
                        Map.of("idZona", zona, "pesoKg", 2, "volumenM3", 0.01),
                        tokenGestor,
                        null);
        String id = exigirExito(simulado, "simular despacho").path("idDespacho").asText();
        var asignado =
                cliente.postJson(
                        API + "/despachos/" + id + "/asignacion",
                        Map.of("idRepartidor", perfil, "idJornada", jornada),
                        tokenGestor,
                        null);
        exigirExito(asignado, "asignar despacho");
        return id;
    }

    static String tokenUsuario(ClienteApi cliente, String perfil) {
        var respuesta =
                cliente.postFormulario(
                        SEGURIDAD + "/pruebas/token-usuario", Map.of("perfil", perfil));
        return exigirExito(respuesta, "token " + perfil).path("accessToken").asText();
    }

    static JsonNode exigirExito(ClienteApi.Respuesta respuesta, String paso) {
        if (respuesta.estado() / 100 != 2 || respuesta.json() == null)
            throw new IllegalStateException(
                    "Preparación fallida en '" + paso + "': HTTP " + respuesta.estado());
        return respuesta.json();
    }

    static String repartidor(
            String id,
            String nombre,
            String dni,
            String telefono,
            String correo,
            String brevete,
            String usuario) {
        return """
                INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,
                    turno_habitual,estado_registro,id_usuario_seguridad,estado_vinculacion,
                    creado_por,actualizado_por)
                VALUES ('%s','%s','Prueba','%s','%s','%s@example.invalid','%s','COMPLETO','ACTIVO',
                    '%s','VINCULADO','%s','%s');
                """
                .formatted(id, nombre, dni, telefono, correo, brevete, usuario, ACTOR, ACTOR);
    }

    static String furgoneta(String id, String placa) {
        return """
                INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,
                    max_paquetes,creado_por,actualizado_por)
                VALUES ('%s','%s','DISPONIBLE',500,4,80,'%s','%s');
                """
                .formatted(id, placa, ACTOR, ACTOR);
    }
}
