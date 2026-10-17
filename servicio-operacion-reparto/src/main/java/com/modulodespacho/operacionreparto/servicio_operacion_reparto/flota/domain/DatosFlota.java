package com.modulodespacho.operacionreparto.servicio_operacion_reparto.flota.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class DatosFlota {
    private DatosFlota() {}
    public enum Turno { MANANA, TARDE, COMPLETO }
    public enum EstadoRegistro { ACTIVO, INACTIVO }
    public enum Vinculacion { PENDIENTE, VINCULADO }
    public enum EstadoFurgoneta {
        DISPONIBLE, EN_MANTENIMIENTO, FUERA_DE_SERVICIO;
        public String persistido() { return this == FUERA_DE_SERVICIO ? "INACTIVA" : name(); }
        public static EstadoFurgoneta desdePersistido(String estado) {
            return "INACTIVA".equals(estado) ? FUERA_DE_SERVICIO : valueOf(estado);
        }
    }
    public record Repartidor(UUID idRepartidor, String nombres, String apellidos, String dni,
            String telefono, String correo, String numeroBrevete, Turno turnoHabitual,
            EstadoRegistro estadoRegistro, Vinculacion estadoVinculacion, UUID usuarioId) {}
    public record Furgoneta(UUID idFurgoneta, String placa, EstadoFurgoneta estado,
            BigDecimal capacidadPesoKg, BigDecimal capacidadVolumenM3, int maximoPaquetes) {}
    public record Pagina<T>(List<T> contenido, int pagina, int tamanoPagina,
            long totalElementos, long totalPaginas) {
        public Pagina(List<T> contenido, int pagina, int tamanoPagina, long totalElementos) {
            this(List.copyOf(contenido), pagina, tamanoPagina, totalElementos,
                    (totalElementos + tamanoPagina - 1) / tamanoPagina);
        }
    }
    public record Contacto(String telefono, String correo, String numeroBrevete, Turno turnoHabitual) {}
    public record AltaRepartidor(String nombres, String apellidos, String dni, Contacto contacto) {}
    public record Capacidad(BigDecimal capacidadPesoKg, BigDecimal capacidadVolumenM3, int maximoPaquetes) {}
}
