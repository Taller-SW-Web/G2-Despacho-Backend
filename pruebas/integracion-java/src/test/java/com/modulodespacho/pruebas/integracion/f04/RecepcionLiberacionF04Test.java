package com.modulodespacho.pruebas.integracion.f04;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modulodespacho.pruebas.integracion.soporte.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** INT-01: 14 comprobaciones migradas; la secuencia se declara porque la recepción es inmutable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecepcionLiberacionF04Test {
    private EntornoCompose entorno;
    private ClienteApi cliente;
    private final Informe informe = new Informe("INT-01 · Recepción y liberación original");
    private String gestor, sinPermiso, despacho, ruta;
    private ObjectNode cuerpo;
    private JsonNode antes, despues, original;
    private static final String API = EntornoCompose.GATEWAY + "/api/v1";
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";

    @BeforeAll
    void preparar() throws Exception {
        entorno = EntornoCompose.iniciar();
        cliente = entorno.cliente();
        gestor = token("gestor");
        sinPermiso = token("sin-rol");
        String zona = UUID.randomUUID().toString(), distrito = UUID.randomUUID().toString();
        String perfil = UUID.randomUUID().toString(), vehiculo = UUID.randomUUID().toString();
        entorno.sql("despacho_gestion", """
            INSERT INTO distrito(id,nombre) VALUES ('%1$s','Distrito INT01');
            INSERT INTO zonas(id,nombre,estado) VALUES ('%2$s','Zona INT01','ACTIVO');
            INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('%2$s','%1$s');
            INSERT INTO tarifas_zona(zona_id,tarifa_base,plazo_max_dias) VALUES ('%2$s',10,1);
            """.formatted(distrito,zona));
        entorno.sql("despacho_operacion", """
            INSERT INTO repartidores(id,nombres,apellidos,dni,telefono,correo,numero_brevete,
                turno_habitual,estado_registro,id_usuario_seguridad,estado_vinculacion,creado_por,actualizado_por)
            VALUES ('%1$s','Contrato','INT01','12345678','999111222','int01@example.invalid','INT01',
                'COMPLETO','ACTIVO','22222222-2222-4222-8222-222222222222','VINCULADO','%3$s','%3$s');
            INSERT INTO furgonetas(id,placa,estado,capacidad_max_kg,capacidad_max_m3,max_paquetes,creado_por,actualizado_por)
            VALUES ('%2$s','INT-001','DISPONIBLE',500,4,80,'%3$s','%3$s');
            """.formatted(perfil,vehiculo,ACTOR));
        String jornada = post("/jornadas", Map.of("idRepartidor",perfil,"idFurgoneta",vehiculo,
                "idZona",zona,"fecha",LocalDate.now(ZoneId.of("America/Lima")).toString()), gestor,201).path("idJornada").asText();
        despacho = post("/despachos/simulaciones",Map.of("idZona",zona,"pesoKg",2,"volumenM3",.01),gestor,201).path("idDespacho").asText();
        post("/despachos/"+despacho+"/asignacion",Map.of("idRepartidor",perfil,"idJornada",jornada),gestor,200);
        String tecnico = cliente.postFormulario(EntornoCompose.SEGURIDAD+"/api/v1/auth/token",
                Map.of("grant_type","client_credentials","client_id","modulo-despacho",
                       "client_secret","despacho-local-no-produccion")).exigirJson(200).path("accessToken").asText();
        assertEquals("FALLIDO",entorno.postInterno("/internal/v1/despachos/"+despacho+"/transiciones",
                Map.of("estadoDestino","FALLIDO","idRepartidor",perfil,"idJornada",jornada,
                       "motivo","NO_INTENTADO","comentario","INT01 sintético","ejecutor",perfil,"origen","MANUAL"),tecnico).path("estado").asText());
        ruta = "/despachos/"+despacho;
        JsonNode detalle = cliente.get(API+ruta+"/incidencia",gestor).exigirJson(200);
        cuerpo = ClienteApi.JSON.createObjectNode()
                .put("idIncidencia",detalle.path("incidencia").path("idIncidencia").asText())
                .put("versionDespacho",detalle.path("versionDespacho").asInt()).put("selloIntacto",false);
        antes = instantanea();
        assertEquals(1,antes.path("reservas").size());
        assertEquals("ACTIVA",antes.path("reservas").get(0).path("estado").asText());
        assertEquals(2,antes.path("ocupacion").path("pesoKg").asDouble());
        assertEquals(.01,antes.path("ocupacion").path("volumenM3").asDouble());
        assertEquals(1,antes.path("ocupacion").path("paquetes").asInt());
        for (String campo : List.of("recepciones","historial","solicitudes"))
            assertEquals(0,antes.path("gestion").path(campo).asInt());
        informe.dato("idDespacho",despacho);
        informe.dato("persistenciaInicial",antes);
    }

    @AfterAll
    void cerrar() throws Exception {
        if (entorno == null) return;
        try {
            if (despues != null) informe.dato("persistenciaFinal",despues);
            informe.escribir(entorno.informes(),"int-01.json");
            entorno.guardarRegistros();
        } finally { entorno.close(); }
    }

    @Test @Order(1) void sinToken() throws Exception { rechazar(null,cuerpo,401,null); }
    @Test @Order(2) void sinPermiso() throws Exception { rechazar(sinPermiso,cuerpo,403,null); }

    Stream<String> invalidos() {
        return Stream.of("sello_omitido","sello_nulo","sello_texto","version_decimal","campo_ajeno","observacion_excesiva");
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidos") @Order(3)
    void datosInvalidos(String caso) throws Exception {
        ObjectNode c = cuerpo.deepCopy();
        switch (caso) {
            case "sello_omitido" -> c.remove("selloIntacto");
            case "sello_nulo" -> c.putNull("selloIntacto");
            case "sello_texto" -> c.put("selloIntacto","false");
            case "version_decimal" -> c.put("versionDespacho",cuerpo.path("versionDespacho").asDouble());
            case "campo_ajeno" -> c.put("usuario","ajeno");
            case "observacion_excesiva" -> c.put("observacion","😀".repeat(501));
        }
        rechazar(gestor,c,400,"DESP_ERROR_DATOS_RECEPCION_INVALIDOS");
    }
    @Test @Order(4) void versionObsoleta() throws Exception {
        rechazar(gestor,cuerpo.deepCopy().put("versionDespacho",cuerpo.path("versionDespacho").asInt()-1),
                409,"DESP_ERROR_DESPACHO_DESACTUALIZADO");
    }
    @Test @Order(5) void incidenciaAjena() throws Exception {
        rechazar(gestor,cuerpo.deepCopy().put("idIncidencia",UUID.randomUUID().toString()),
                409,"DESP_ERROR_DESPACHO_DESACTUALIZADO");
    }
    @Test @Order(6) void recepcionValida() {
        var respuesta = cliente.postJson(API+ruta+"/recepcion-centro",cuerpo,gestor,null);
        original = respuesta.exigirJson(202);
        assertEquals("application/json",tipo(respuesta));
        assertEquals("no-store",respuesta.cabeceras().firstValue("Cache-Control").orElse(""));
        JsonNode r = original.path("recepcion"), l = r.path("liberacionCapacidad");
        assertEquals(despacho,original.path("idDespacho").asText());
        assertEquals(cuerpo.path("idIncidencia"),original.path("idIncidencia"));
        assertEquals("FALLIDO",original.path("estado").asText());
        assertEquals(cuerpo.path("versionDespacho").asInt()+1,original.path("versionDespacho").asInt());
        assertEquals("RECIBIDO_EN_CENTRO",r.path("estado").asText());
        assertFalse(r.path("selloIntacto").asBoolean(true));
        assertTrue(r.path("observacion").isNull());
        assertEquals(ACTOR,r.path("idUsuario").asText());
        assertEquals(ZoneOffset.UTC,OffsetDateTime.parse(r.path("confirmadaEn").asText()).getOffset());
        assertEquals("PENDIENTE",l.path("estado").asText());
        assertFalse(l.path("idSolicitud").asText().isBlank());
        assertFalse(l.path("requiereRevision").asBoolean(true));
        assertTrue(l.path("confirmadaEn").isNull());
        assertEquals(ZoneOffset.UTC,OffsetDateTime.parse(l.path("actualizadaEn").asText()).getOffset());
        informe.verificar("INT-01","recepcion_202_sello_false_sin_observacion",true);
    }
    @Test @Order(7) void liberacionOriginal() throws Exception {
        assertNotNull(original,"No continuar si la recepción previa falló");
        Instant limite = Instant.now().plusSeconds(90);
        boolean confirmada = false;
        while (Instant.now().isBefore(limite)) {
            var d = cliente.get(API+ruta+"/incidencia",gestor).exigirJson(200);
            if ("CONFIRMADA".equals(d.path("incidencia").path("recepcion").path("liberacionCapacidad").path("estado").asText())) {
                confirmada = true; break;
            }
            Thread.sleep(1000);
        }
        assertTrue(confirmada,"No se confirmó la liberación en 90 segundos");
        despues = instantanea();
        ObjectNode esperado = (ObjectNode) antes.path("gestion").deepCopy();
        esperado.put("version",esperado.path("version").asInt()+1).put("recepciones",1).put("historial",1).put("solicitudes",1);
        assertEquals(esperado,despues.path("gestion"));
        assertEquals(1,despues.path("reservas").size());
        var reserva = despues.path("reservas").get(0);
        assertEquals(antes.path("reservas").get(0).path("id"),reserva.path("id"));
        assertEquals("LIBERADA",reserva.path("estado").asText());
        assertFalse(reserva.path("liberadoEn").isNull());
        for (String campo : List.of("pesoKg","volumenM3","paquetes"))
            assertEquals(0,despues.path("ocupacion").path(campo).decimalValue().signum());
        informe.verificar("INT-01","liberacion_real_reserva_original",true);
    }
    @Test @Order(8) void repeticionExacta() throws Exception {
        assertNotNull(despues);
        assertEquals(original,post(ruta+"/recepcion-centro",cuerpo,gestor,202));
        assertEquals(despues,instantanea());
        informe.verificar("INT-01","repeticion_202_respuesta_original_sin_duplicados",true);
    }
    @Test @Order(9) void contenidoDistinto() throws Exception {
        var r = cliente.postJson(API+ruta+"/recepcion-centro",cuerpo.deepCopy().put("selloIntacto",true),gestor,null);
        problema(r,409,"DESP_ERROR_OPERACION_REUTILIZADA");
        assertEquals(despues,instantanea());
        informe.verificar("INT-01","contenido_distinto_409_sin_sobrescritura",true);
    }
    private void rechazar(String token,ObjectNode datos,int estado,String codigo) throws Exception {
        var r = cliente.postJson(API+ruta+"/recepcion-centro",datos,token,null);
        problema(r,estado,codigo);
        assertEquals(antes,instantanea(),"El rechazo modificó estado o capacidad");
        informe.verificar("INT-01","rechazo_"+estado+"_"+datos.toString(),true);
    }
    private void problema(ClienteApi.Respuesta r,int estado,String codigo) {
        JsonNode p = r.exigirJson(estado);
        assertEquals("application/problem+json",tipo(r));
        assertEquals(estado,p.path("status").asInt());
        for (String campo : List.of("code","title","detail","instance","correlationId","timestamp"))
            assertFalse(p.path(campo).asText().isBlank(),campo);
        if (p.has("type")) assertFalse(p.path("type").asText().isBlank());
        for (String campo : List.of("recepcion","incidencia","historial","contenido")) assertFalse(p.has(campo));
        if (codigo != null) assertEquals(codigo,p.path("code").asText());
        if (estado == 400) assertFalse(p.path("errores").isEmpty());
        if (estado == 401) assertTrue(r.cabeceras().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
    }
    private String tipo(ClienteApi.Respuesta r) { return r.cabeceras().firstValue("Content-Type").orElse("").split(";")[0]; }
    private JsonNode post(String ruta,Object cuerpo,String token,int estado) {
        return cliente.postJson(API+ruta,cuerpo,token,null).exigirJson(estado);
    }
    private String token(String perfil) {
        return cliente.postFormulario(EntornoCompose.SEGURIDAD+"/pruebas/token-usuario",Map.of("perfil",perfil))
                .exigirJson(200).path("accessToken").asText();
    }
    private JsonNode instantanea() throws Exception {
        ObjectNode r = ClienteApi.JSON.createObjectNode();
        r.set("gestion",sql("despacho_gestion","""
            SELECT json_build_object('estado',estado,'version',version,'intentos',numero_intentos,
            'recepciones',(SELECT count(*) FROM intentos_entrega WHERE despacho_id=d.id AND recibido_en_centro_en IS NOT NULL),
            'historial',(SELECT count(*) FROM historial_estados_despacho WHERE despacho_id=d.id AND tipo_operacion='RECEPCION_EN_CENTRO'),
            'solicitudes',(SELECT count(*) FROM liberaciones_capacidad WHERE despacho_id=d.id),
            'eventos',(SELECT count(*) FROM eventos_salientes WHERE despacho_id=d.id))
            FROM despachos d WHERE id='%s';
            """.formatted(despacho)));
        r.set("reservas",sql("despacho_operacion","SELECT coalesce(json_agg(json_build_object('id',id,'estado',estado,'liberadoEn',liberado_en) ORDER BY id),'[]'::json) FROM reservas_capacidad WHERE despacho_id_externo='"+despacho+"';"));
        r.set("ocupacion",sql("despacho_operacion","SELECT json_build_object('pesoKg',coalesce(sum(peso_kg),0),'volumenM3',coalesce(sum(volumen_m3),0),'paquetes',coalesce(sum(cantidad_paquetes),0)) FROM reservas_capacidad WHERE estado='ACTIVA' AND asignacion_diaria_id IN (SELECT asignacion_diaria_id FROM reservas_capacidad WHERE despacho_id_externo='"+despacho+"');"));
        return r;
    }
    private JsonNode sql(String base,String sentencia) throws Exception {
        return ClienteApi.JSON.readTree(String.join("",entorno.consultar(base,sentencia)));
    }
}
