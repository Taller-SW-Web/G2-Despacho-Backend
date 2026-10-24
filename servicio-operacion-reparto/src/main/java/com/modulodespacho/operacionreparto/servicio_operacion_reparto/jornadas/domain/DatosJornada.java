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
    public record Apertura(UUID idRepartidor, UUID idFurgoneta, UUID idZona, LocalDate fecha) {}
    public record JornadaBase(UUID id, UUID repartidor, UUID furgoneta, String placa, UUID zona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, boolean enCamino, boolean pendientes) {
        public boolean activa() { return "ACTIVA".equals(estado); }
        public String estadoOperativo() {
            if (!activa()) return "FUERA_DE_TURNO";
            if (!limites.menos(ocupacion).positiva()) return "SATURADO";
            return enCamino ? "EN_RUTA" : "DISPONIBLE";
        }
        public Jornada vista() {
            return new Jornada(id, repartidor, furgoneta, placa, zona, fecha, estado, cerradaEn, limites, ocupacion,
                    limites.menos(ocupacion), new Porcentajes(porcentaje(ocupacion.pesoKg, limites.pesoKg),
                    porcentaje(ocupacion.volumenM3, limites.volumenM3), porcentaje(BigDecimal.valueOf(ocupacion.paquetes), BigDecimal.valueOf(limites.paquetes))));
        }
    }
    private static BigDecimal porcentaje(BigDecimal usado, BigDecimal limite) {
        if (limite.signum() <= 0) throw ErrorJornada.noDisponible();
        return usado.multiply(BigDecimal.valueOf(100)).divide(limite, 6, RoundingMode.HALF_UP);
    }
    public record Jornada(UUID idJornada, UUID idRepartidor, UUID idFurgoneta, String placa, UUID idZona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente, Porcentajes porcentajeOcupacion) {}
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
            String estadoOperativo, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente, Porcentajes porcentajeOcupacion) {}
    public record DespachoResumen(UUID idDespacho, String estado, Integer secuenciaRuta) {}
    public record Detalle(UUID idJornada, UUID idRepartidor, UUID idFurgoneta, String placa, UUID idZona, LocalDate fecha,
            String estado, Instant cerradaEn, Capacidad limites, Capacidad ocupacion, Capacidad capacidadRemanente,
            Porcentajes porcentajeOcupacion, List<DespachoResumen> despachos) {
        public static Detalle de(Jornada j, List<DespachoResumen> despachos) { return new Detalle(j.idJornada,j.idRepartidor,j.idFurgoneta,j.placa,j.idZona,j.fecha,j.estado,j.cerradaEn,j.limites,j.ocupacion,j.capacidadRemanente,j.porcentajeOcupacion,despachos); }
    }
    public record Contexto(UUID idRepartidor, String estadoOperativo, Jornada jornada) {}
    /** Marcas del ciclo vigente informadas por Gestión: asignado, en camino y cierre del intento. */
    public record Hitos(Instant asignadoEn, Instant enCaminoEn, Instant entregadoEn, Instant fallidoEn) {}
    public record DespachoRuta(UUID idDespacho, String codigoRastreoInterno, String estado,
            Integer secuenciaRuta, LocalDate fechaProgramada, int numeroIntentos, Integer maximoIntentos,
            String destinatario, String direccion, String distrito, String motivoFallo, Hitos hitos) {}
    public record RutaRepartidor(UUID idJornada, LocalDate fecha, String estadoOperativo,
            List<DespachoRuta> despachos) {}
    public record DetalleDespacho(UUID idJornada, UUID idDespacho, String codigoRastreoInterno, String estado,
            Integer secuenciaRuta, LocalDate fechaProgramada, int numeroIntentos, Integer maximoIntentos,
            Destinatario destinatario, Destino destino, String motivoFallo, Hitos hitos) {}
    /** Paquete que el repartidor devuelve al centro: fallido con o sin intento consumido. */
    public record PaqueteDevolucion(UUID idDespacho, String codigoRastreoInterno, String direccion, String distrito, String motivoFallo) {}
    public record ResumenJornada(UUID idJornada, LocalDate fecha, String estadoJornada, Instant cerradaEn, long asignados, long entregados,
            long fallidosConIntento, long noIntentados, long pendientes, List<PaqueteDevolucion> paquetesPorDevolver) {}
    public record DespachoEnRuta(UUID idRepartidor, UUID idJornada, Proyeccion despacho) {}
    public record SolicitudReserva(UUID idDespacho, UUID idRepartidor, UUID idJornada, Capacidad requerido) {}
    public record ConfirmacionReserva(UUID idReserva, String estado, Capacidad capacidadRemanente) {}
    public record Reserva(UUID id, UUID clave, UUID despacho, UUID jornada, UUID repartidor, Capacidad carga, String estado,
            String motivo, Instant liberadaEn, String respuesta) {}
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
