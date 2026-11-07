package com.modulodespacho.operacionreparto.servicio_operacion_reparto.jornadas.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DatosJornada {
    private DatosJornada() {}
    public record Capacidad(BigDecimal pesoKg, BigDecimal volumenM3, int paquetes) {
        public static Capacidad cero() { return new Capacidad(BigDecimal.ZERO, BigDecimal.ZERO, 0); }
        public Capacidad menos(Capacidad otra) { return new Capacidad(pesoKg.subtract(otra.pesoKg), volumenM3.subtract(otra.volumenM3), Math.subtractExact(paquetes, otra.paquetes)); }
        public boolean positiva() { return pesoKg.signum() > 0 && volumenM3.signum() > 0 && paquetes > 0; }
        public boolean admite(Capacidad otra) { return pesoKg.compareTo(otra.pesoKg) >= 0 && volumenM3.compareTo(otra.volumenM3) >= 0 && paquetes >= otra.paquetes; }
        public boolean mismaCarga(Capacidad otra) { return pesoKg.compareTo(otra.pesoKg) == 0 && volumenM3.compareTo(otra.volumenM3) == 0 && paquetes == otra.paquetes; }
    }
    public record Porcentajes(BigDecimal peso, BigDecimal volumen, BigDecimal paquetes) {}
    public record Perfil(UUID id, String nombre, String estadoRegistro, String estadoVinculacion) {
        public boolean habilitado() { return "ACTIVO".equals(estadoRegistro) && "VINCULADO".equals(estadoVinculacion); }
    }
    public record Vehiculo(UUID id, String placa, String estado, Capacidad limites) {}
    public static final String ENTREGAS = "ENTREGAS";
    public static final String RECOJOS = "RECOJOS";
    public record Apertura(UUID idRepartidor, UUID idFurgoneta, UUID idZona, LocalDate fecha, String tipoOperacion) {
        public Apertura(UUID idRepartidor, UUID idFurgoneta, UUID idZona, LocalDate fecha) { this(idRepartidor, idFurgoneta, idZona, fecha, ENTREGAS); }
    }
    public record JornadaBase(UUID id, UUID repartidor, UUID furgoneta, String placa, UUID zona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, boolean enCamino, boolean pendientes, String tipoOperacion) {
        public JornadaBase(UUID id, UUID repartidor, UUID furgoneta, String placa, UUID zona, LocalDate fecha,
                String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, boolean enCamino, boolean pendientes) {
            this(id, repartidor, furgoneta, placa, zona, fecha, estado, cerradaEn, limites, ocupacion, enCamino, pendientes, ENTREGAS);
        }
        public boolean activa() { return "ACTIVA".equals(estado); }
        public String estadoOperativo() {
            if (!activa()) return "FUERA_DE_TURNO";
            if (!limites.menos(ocupacion).positiva()) return "SATURADO";
            return enCamino ? "EN_RUTA" : "DISPONIBLE";
        }
        public Jornada vista() {
            return new Jornada(id, repartidor, furgoneta, placa, zona, fecha, estado, cerradaEn, limites, ocupacion,
                    limites.menos(ocupacion), new Porcentajes(porcentaje(ocupacion.pesoKg, limites.pesoKg),
                    porcentaje(ocupacion.volumenM3, limites.volumenM3), porcentaje(BigDecimal.valueOf(ocupacion.paquetes), BigDecimal.valueOf(limites.paquetes))), tipoOperacion);
        }
    }
    private static BigDecimal porcentaje(BigDecimal usado, BigDecimal limite) {
        if (limite.signum() <= 0) throw ErrorJornada.noDisponible();
        return usado.multiply(BigDecimal.valueOf(100)).divide(limite, 6, RoundingMode.HALF_UP);
    }
    public record Jornada(UUID idJornada, UUID idRepartidor, UUID idFurgoneta, String placa, UUID idZona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente, Porcentajes porcentajeOcupacion, String tipoOperacion) {
        public Jornada(UUID idJornada, UUID idRepartidor, UUID idFurgoneta, String placa, UUID idZona, LocalDate fecha,
                String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente, Porcentajes porcentajeOcupacion) {
            this(idJornada, idRepartidor, idFurgoneta, placa, idZona, fecha, estado, cerradaEn, limites, ocupacion, capacidadRemanente, porcentajeOcupacion, ENTREGAS);
        }
    }
    public record FilaBase(Perfil perfil, Vehiculo vehiculo, JornadaBase jornada) {
        public String estadoOperativo() { return jornada == null ? "FUERA_DE_TURNO" : jornada.estadoOperativo(); }
        public FilaMonitoreo vista() { return new FilaMonitoreo(perfil.id, perfil.nombre, estadoOperativo(), jornada == null ? null : jornada.vista()); }
        public boolean disponible(LocalDate fecha) { return perfil.habilitado() && jornada != null && jornada.activa()
                && jornada.fecha.equals(fecha) && "DISPONIBLE".equals(vehiculo.estado) && jornada.limites.menos(jornada.ocupacion).positiva(); }
    }
    public record FilaMonitoreo(UUID idRepartidor, String nombre, String estadoOperativo, Jornada jornada) {}
    public record Resumen(long disponible, long enRuta, long saturado, long fueraDeTurno) {}
    public record Monitoreo(List<FilaMonitoreo> contenido, int pagina, int tamanoPagina, long totalElementos, int totalPaginas, Resumen resumen, Instant consultadoEn) {}
    public record Candidato(UUID idRepartidor, UUID idJornada, String nombre, UUID idZona, UUID idFurgoneta, String placa,
            String estadoOperativo, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente, Porcentajes porcentajeOcupacion, String tipoOperacion) {}
    public record RecojoResumen(UUID idRecojo, String estado, Integer secuenciaRuta) {}
    public record DespachoResumen(UUID idDespacho, String estado, Integer secuenciaRuta) {}
    public record Detalle(UUID idJornada, UUID idRepartidor, UUID idFurgoneta, String placa, UUID idZona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente,
            Porcentajes porcentajeOcupacion, List<DespachoResumen> despachos, String tipoOperacion, List<RecojoResumen> recojos) {
        public static Detalle de(Jornada j, List<DespachoResumen> despachos) { return de(j, despachos, List.of()); }
        public static Detalle de(Jornada j, List<DespachoResumen> despachos, List<RecojoResumen> recojos) { return new Detalle(j.idJornada,j.idRepartidor,j.idFurgoneta,j.placa,j.idZona,j.fecha,j.estado,j.cerradaEn,j.limites,j.ocupacion,j.capacidadRemanente,j.porcentajeOcupacion,despachos,j.tipoOperacion,recojos); }
    }
    public record Contexto(UUID idRepartidor, String estadoOperativo, Jornada jornada) {}
    /** Marcas del ciclo vigente informadas por Gestión: asignado, en camino y cierre del intento. */
    public record Hitos(Instant asignadoEn, Instant enCaminoEn, Instant entregadoEn, Instant fallidoEn) {}
    public record DespachoRuta(UUID idDespacho, String codigoRastreoInterno, String estado,
            Integer secuenciaRuta, LocalDate fechaProgramada, int numeroIntentos, Integer maximoIntentos,
            String destinatario, String direccion, String distrito, String motivoFallo, Hitos hitos) {}
    public record RutaRepartidor(UUID idJornada, LocalDate fecha, String estadoOperativo,
            List<DespachoRuta> despachos, String tipoOperacion, List<RecojoRuta> recojos) {}
    public record DetalleDespacho(UUID idJornada, UUID idDespacho, String codigoRastreoInterno, String estado,
            Integer secuenciaRuta, LocalDate fechaProgramada, int numeroIntentos, Integer maximoIntentos,
            Destinatario destinatario, Destino destino, String motivoFallo, Hitos hitos) {}
    /** Paquete que el repartidor devuelve al centro: fallido con o sin intento consumido. */
    public record PaqueteDevolucion(UUID idDespacho, String codigoRastreoInterno, String direccion, String distrito, String motivoFallo) {}
    public record ResumenJornada(UUID idJornada, LocalDate fecha, String estadoJornada, Instant cerradaEn, long asignados, long entregados,
            long fallidosConIntento, long noIntentados, long pendientes, List<PaqueteDevolucion> paquetesPorDevolver,
            String tipoOperacion, long recojosRealizados, long recojosNoRealizados, List<ProductoPorEntregar> productosPorEntregar) {}
    public record DespachoEnRuta(UUID idRepartidor, UUID idJornada, Proyeccion despacho) {}
    /** idRecojo solo llega en un recojo; idDespacho es siempre el despacho original. */
    public record SolicitudReserva(UUID idDespacho, UUID idRepartidor, UUID idJornada, Capacidad requerido, UUID idRecojo) {
        public SolicitudReserva(UUID idDespacho, UUID idRepartidor, UUID idJornada, Capacidad requerido) { this(idDespacho, idRepartidor, idJornada, requerido, null); }
        public String tipoRequerido() { return idRecojo == null ? ENTREGAS : RECOJOS; }
    }
    public record ConfirmacionReserva(UUID idReserva, String estado, Capacidad capacidadRemanente) {}
    public record SolicitudTransferencia(UUID idReservaOrigen, UUID idDespacho, UUID idNuevoRepartidor, UUID idNuevaJornada) {}
    public record Transferencia(UUID idReservaOrigen, UUID idDespacho, UUID idNuevaReserva, UUID idNuevaJornada,
            String estado, Capacidad capacidadRemanente, Instant transferidaEn) {}
    public record Reserva(UUID id, UUID clave, UUID despacho, UUID jornada, UUID repartidor, Capacidad carga, String estado,
            String motivo, Instant liberadaEn, String respuesta, UUID recojo) {
        public Reserva(UUID id, UUID clave, UUID despacho, UUID jornada, UUID repartidor, Capacidad carga, String estado,
                String motivo, Instant liberadaEn, String respuesta) { this(id, clave, despacho, jornada, repartidor, carga, estado, motivo, liberadaEn, respuesta, null); }
    }
    public record LineaRecojo(String sku, int cantidad) {}
    public record HitosRecojo(Instant asignadoEn, Instant enCaminoEn, Instant recogidoEn, Instant fallidoEn) {}
    /** Copia del recojo que Gestión comunica por §5.6; el estado canónico sigue en Gestión. */
    public record ProyeccionRecojo(UUID idRecojo, UUID idDespacho, String codigoRastreoInterno, String estado, UUID idJornada,
            UUID idReserva, Integer secuenciaRuta, Destinatario destinatario, Destino destino, List<LineaRecojo> lineas,
            LocalDate fechaProgramada, int numeroIntentos, long version, Instant actualizadoEn, String motivoFallo, HitosRecojo hitos) {
        public ProyeccionRecojo conTransicion(String nuevoEstado, int intentos, long nuevaVersion, Instant registradoEn, String motivo, HitosRecojo nuevosHitos) {
            return new ProyeccionRecojo(idRecojo, idDespacho, codigoRastreoInterno, nuevoEstado, idJornada, idReserva, secuenciaRuta,
                    destinatario, destino, lineas, fechaProgramada, intentos, nuevaVersion, registradoEn, motivo, nuevosHitos);
        }
        public boolean noIntentado() { return "FALLIDO".equals(estado) && "NO_INTENTADO".equals(motivoFallo); }
        public boolean noRealizado() { return "FALLIDO".equals(estado) && !noIntentado(); }
    }
    public record RecojoRuta(UUID idRecojo, UUID idDespacho, String codigoRastreoInterno, String estado, Integer secuenciaRuta,
            LocalDate fechaProgramada, int numeroIntentos, String destinatario, String direccion, String distrito,
            List<LineaRecojo> lineas, String motivoFallo, HitosRecojo hitos) {}
    public record DetalleRecojo(UUID idJornada, UUID idRecojo, UUID idDespacho, String codigoRastreoInterno, String estado, Integer secuenciaRuta,
            LocalDate fechaProgramada, int numeroIntentos, Destinatario destinatario, Destino destino,
            List<LineaRecojo> lineas, String motivoFallo, HitosRecojo hitos) {}
    public record ProductoPorEntregar(UUID idRecojo, String codigoRastreoInterno, List<LineaRecojo> lineas) {}
    public record RecojoEnRuta(UUID idRepartidor, UUID idJornada, ProyeccionRecojo recojo) {}
    public record Liberacion(UUID idReserva, UUID idDespacho, String estado, String motivoLiberacion, Instant liberadaEn) {}
    public record Destinatario(String nombre, String telefono) {}
    public record Destino(String direccion, String referencia, String departamento, String provincia, String distrito, String codigoPostal, Object coordenadas) {}
    public record Proyeccion(UUID idDespacho, String idPedido, String codigoRastreoInterno, String estado, UUID idJornada,
            UUID idReserva, Integer secuenciaRuta, Destinatario destinatario, Destino destino, LocalDate fechaProgramada,
            int numeroIntentos, long version, Instant actualizadoEn, Integer maximoIntentos, String motivoFallo, Hitos hitos) {
        public Proyeccion conTransicion(String nuevoEstado, int intentos, long nuevaVersion, Instant registradoEn, String motivo, Hitos nuevosHitos) {
            return new Proyeccion(idDespacho, idPedido, codigoRastreoInterno, nuevoEstado, idJornada, idReserva, secuenciaRuta,
                    destinatario, destino, fechaProgramada, intentos, nuevaVersion, registradoEn, maximoIntentos, motivo, nuevosHitos);
        }
        public boolean noIntentado() { return "NO_INTENTADO".equals(estado) || "FALLIDO".equals(estado) && "NO_INTENTADO".equals(motivoFallo); }
        /** Las proyecciones previas al motivo conservan la regla anterior basada en el contador. */
        public boolean fallidoConIntento() { return "FALLIDO".equals(estado) && !noIntentado() && (motivoFallo != null || numeroIntentos > 0); }
    }
}
