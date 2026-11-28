package com.modulodespacho.pruebas.integracion.f02;

import static com.modulodespacho.pruebas.integracion.f02.EscenarioRutasF02.*;
import com.modulodespacho.pruebas.integracion.soporte.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Solo se invoca explícitamente. Cada preparación exige un proyecto desechable limpio. */
class PreparacionPersonaB {
    @Test
    void preparar() throws Exception {
        String caso = System.getProperty("caso", "");
        if (!Set.of("PERF-03", "PERF-04").contains(caso))
            throw new IllegalArgumentException("Indica -Dcaso=PERF-03 o PERF-04");
        Path overlay = Files.createTempFile("hito5-productos-", ".yaml");
        Files.writeString(overlay,"services:\n  gestion:\n    environment:\n      SPRING_PROFILES_ACTIVE: productos-simulados\n");
        var entorno = EntornoCompose.iniciarConservado("hito5-persona-b",List.of(overlay));
        var cliente = entorno.cliente();
        String gestor = tokenUsuario(cliente,"gestor");
        assertEquals(List.of("0"),entorno.consultar("despacho_gestion","SELECT count(*) FROM despachos;"),
                "Recrea exclusivamente el proyecto hito5-persona-b antes de preparar otra corrida");
        assertEquals(List.of("0"),entorno.consultar("despacho_gestion","SELECT count(*) FROM cotizaciones;"));
        String zona = UUID.randomUUID().toString(), distrito = UUID.randomUUID().toString();
        entorno.sql("despacho_gestion","""
            INSERT INTO distrito(id,nombre) VALUES ('%1$s','Distrito Persona B');
            INSERT INTO zonas(id,nombre,estado) VALUES ('%2$s','Zona Persona B','ACTIVO');
            INSERT INTO zonas_distritos(zona_id,distrito_id) VALUES ('%2$s','%1$s');
            INSERT INTO tarifas_zona(zona_id,tarifa_base,peso_base_kg,recargo_kg_adicional,factor_volumetrico,plazo_max_dias,moneda)
            VALUES ('%2$s',10,3,2,1.25,2,'PEN');
            """.formatted(distrito,zona));
        Path datos = Path.of("../rendimiento/datos");
        Files.createDirectories(datos);
        if ("PERF-04".equals(caso)) {
            List<String> filas = new ArrayList<>();
            filas.add("idDespacho,idRepartidor,idJornada");
            for (int j=0;j<20;j++) {
                String perfil=UUID.randomUUID().toString(), vehiculo=UUID.randomUUID().toString();
                entorno.sql("despacho_operacion",repartidor(perfil,"CargaB"+j,"8000%04d".formatted(j),
                        "97700%04d".formatted(j),"cargab"+j,"B-"+j,UUID.randomUUID().toString())
                        +furgoneta(vehiculo,"PB-%03d".formatted(j)));
                String jornada=exigirExito(cliente.postJson(API+"/jornadas",
                        Map.of("idRepartidor",perfil,"idFurgoneta",vehiculo,"idZona",zona,
                               "fecha",LocalDate.now(ZoneId.of("America/Lima")).toString()),gestor,null),"jornada").path("idJornada").asText();
                // 40 paquetes por jornada, por debajo del límite 80, incluso si se usa todo el CSV.
                for (int d=0;d<40;d++) {
                    String id=exigirExito(cliente.postJson(API+"/despachos/simulaciones",
                            Map.of("idZona",zona,"pesoKg",2,"volumenM3",.01),gestor,null),"simulación").path("idDespacho").asText();
                    filas.add(id+","+perfil+","+jornada);
                }
            }
            // Entrelazar jornadas para no concentrar las escrituras en una sola.
            List<String> mezcladas = new ArrayList<>(filas.subList(1,filas.size()));
            Collections.shuffle(mezcladas,new Random(504));
            String[] control = mezcladas.remove(0).split(",");
            Files.writeString(datos.resolve("control-asignacion.json"),ClienteApi.JSON.writeValueAsString(
                    Map.of("idDespacho",control[0],"idRepartidor",control[1],"idJornada",control[2])));
            mezcladas.add(0,filas.get(0));
            Files.write(datos.resolve("asignaciones.csv"),mezcladas);
            assertEquals(800,mezcladas.size()); // 799 de carga más cabecera; control separado.
        }
        Files.writeString(datos.resolve("preparacion-persona-b.json"),ClienteApi.JSON.writeValueAsString(
                Map.of("caso",caso,"proyecto",entorno.proyecto(),"zona",zona,
                       "fechaUtc",Instant.now().toString(),"commit",System.getProperty("commit","no declarado"))));
        System.out.println("Preparado "+caso+" en "+entorno.proyecto()+". Recrear antes de repetir.");
    }
}
