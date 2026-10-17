package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.application.LectorDatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.EstadoJornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Jornada;
import com.modulodespacho.gestiondespachos.servicio_gestion_despachos.incidencias.domain.DatosOperativos.Repartidor;

/** Consumidor de Operación §5.4. Sin caché, escrituras ni acceso a bases ajenas. */
public final class OperacionIncidenciasHttp implements LectorDatosOperativos {

    private static final int TAMANO_LOTE = 100;
    private static final String RUTA = "/internal/v1/operacion/consulta-incidencias";
    private final RestClient cliente;
    private final Supplier<String> proveedorToken;

    /**
     * El ensamblaje debe suministrar un cliente interno con URL y tiempos de espera acotados,
     * y un proveedor de credencial técnica vigente. Nunca se reenvía el JWT humano.
     */
    public OperacionIncidenciasHttp(RestClient cliente, Supplier<String> proveedorToken) {
        this.cliente = Objects.requireNonNull(cliente);
        this.proveedorToken = Objects.requireNonNull(proveedorToken);
    }

    @Override
    public DatosOperativos consultar(Collection<String> idsRepartidor, Collection<String> idsJornada) {
        List<String> repartidoresUnicos = identificadoresUnicos(idsRepartidor);
        List<String> jornadasUnicas = identificadoresUnicos(idsJornada);
        Map<String, Repartidor> repartidores = new LinkedHashMap<>();
        Map<String, Jornada> jornadas = new LinkedHashMap<>();
        repartidoresUnicos.forEach(id -> repartidores.put(id, new Repartidor(id, null)));
        jornadasUnicas.forEach(id -> jornadas.put(id, Jornada.desconocida(id, null)));

        int cantidad = Math.max(repartidoresUnicos.size(), jornadasUnicas.size());
        for (int inicio = 0; inicio < cantidad; inicio += TAMANO_LOTE) {
            List<String> loteRepartidores = lote(repartidoresUnicos, inicio);
            List<String> loteJornadas = lote(jornadasUnicas, inicio);
            Respuesta respuesta = solicitar(loteRepartidores, loteJornadas);
            if (respuesta != null && respuesta.consultadoEn() != null) {
                incorporarRepartidores(loteRepartidores, respuesta.repartidores(), repartidores);
                incorporarJornadas(loteJornadas, respuesta, jornadas);
            }
        }
        return new DatosOperativos(repartidores, jornadas);
    }

    private Respuesta solicitar(List<String> idsRepartidor, List<String> idsJornada) {
        try {
            String token = proveedorToken.get();
            if (token == null || token.isBlank()) {
                return null;
            }
            return cliente.post().uri(RUTA)
                    .headers(encabezados -> encabezados.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Solicitud(idsRepartidor, idsJornada))
                    .retrieve().body(Respuesta.class);
        } catch (RestClientException error) {
            // Incluye timeout, respuesta inválida y rechazo de la credencial técnica.
            // No se filtran mensajes internos ni se reutilizan lecturas de una consulta anterior.
            return null;
        }
    }

    private static void incorporarRepartidores(List<String> solicitados, List<Repartidor> respuesta,
            Map<String, Repartidor> destino) {
        if (respuesta == null) return;
        Set<String> permitidos = new HashSet<>(solicitados);
        Set<String> vistos = new HashSet<>();
        for (Repartidor repartidor : respuesta) {
            if (repartidor == null || !permitidos.contains(repartidor.idRepartidor())) continue;
            String id = repartidor.idRepartidor();
            boolean unico = vistos.add(id);
            String nombre = unico && repartidor.nombre() != null && !repartidor.nombre().isBlank()
                    ? repartidor.nombre() : null;
            destino.put(id, new Repartidor(id, nombre));
        }
    }

    private static void incorporarJornadas(List<String> solicitadas, Respuesta respuesta,
            Map<String, Jornada> destino) {
        solicitadas.forEach(id -> destino.put(id, Jornada.desconocida(id, respuesta.consultadoEn())));
        if (respuesta.jornadas() == null) return;
        Set<String> permitidas = new HashSet<>(solicitadas);
        Set<String> vistas = new HashSet<>();
        for (JornadaRespuesta jornada : respuesta.jornadas()) {
            if (jornada == null || !permitidas.contains(jornada.idJornada())) continue;
            String id = jornada.idJornada();
            EstadoJornada estado = EstadoJornada.DESCONOCIDA;
            if (vistas.add(id)) {
                if ("CERRADA".equals(jornada.estado()) && jornada.cerradaEn() != null) {
                    estado = EstadoJornada.CERRADA;
                } else if ("ACTIVA".equals(jornada.estado()) && jornada.cerradaEn() == null) {
                    estado = EstadoJornada.ACTIVA;
                }
            }
            destino.put(id, new Jornada(id, estado,
                    estado == EstadoJornada.CERRADA ? jornada.cerradaEn() : null, respuesta.consultadoEn()));
        }
    }

    private static List<String> identificadoresUnicos(Collection<String> identificadores) {
        Objects.requireNonNull(identificadores);
        if (identificadores.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Los identificadores operativos no pueden estar vacíos");
        }
        // Los identificadores son opacos: no se convierten a UUID ni se modifica su contenido.
        return List.copyOf(new LinkedHashSet<>(identificadores));
    }

    private static List<String> lote(List<String> ids, int inicio) {
        return inicio >= ids.size() ? List.of() : ids.subList(inicio, Math.min(inicio + TAMANO_LOTE, ids.size()));
    }

    public record Solicitud(List<String> idsRepartidor, List<String> idsJornada) {}
    public record JornadaRespuesta(String idJornada, String estado, Instant cerradaEn) {}
    public record Respuesta(List<Repartidor> repartidores, List<JornadaRespuesta> jornadas, Instant consultadoEn) {}
}
