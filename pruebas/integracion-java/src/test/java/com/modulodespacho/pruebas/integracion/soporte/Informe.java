package com.modulodespacho.pruebas.integracion.soporte;

import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Informe JSON por caso (ID, datos sintéticos y cada aserción con su resultado), complementario
 * al XML de Surefire. Solo registra identificadores sintéticos: nunca tokens ni cuerpos remotos.
 */
public final class Informe {
    private final String titulo;
    private final Map<String, Object> datos = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> casos = new LinkedHashMap<>();

    public Informe(String titulo) {
        this.titulo = titulo;
    }

    public synchronized void dato(String clave, Object valor) {
        datos.put(clave, valor);
    }

    /** Registra la aserción y falla la prueba si no se cumple. */
    public synchronized void verificar(String caso, String nombre, boolean condicion, String detalle) {
        var paso = new LinkedHashMap<String, Object>();
        paso.put("comprobacion", nombre);
        paso.put("correcto", condicion);
        if (!condicion) paso.put("detalle", detalle);
        casos.computeIfAbsent(caso, clave -> new ArrayList<>()).add(paso);
        if (!condicion) throw new AssertionError(caso + " · " + nombre + ": " + detalle);
    }

    public synchronized void verificar(String caso, String nombre, boolean condicion) {
        verificar(caso, nombre, condicion, "no se cumplió");
    }

    public synchronized void escribir(Path directorio, String archivo) throws IOException {
        Files.createDirectories(directorio);
        var contenido = new LinkedHashMap<String, Object>();
        contenido.put("verificacion", titulo);
        contenido.put("fechaUtc", Instant.now().toString());
        contenido.put("commitBackend", System.getProperty("commit", "no declarado"));
        contenido.put("datosSinteticos", datos);
        contenido.put("casos", casos);
        ClienteApi.JSON
                .copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(directorio.resolve(archivo).toFile(), contenido);
    }
}
