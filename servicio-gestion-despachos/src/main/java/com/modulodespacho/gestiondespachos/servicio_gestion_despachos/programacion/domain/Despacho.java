package com.modulodespacho.gestiondespachos.servicio_gestion_despachos.programacion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Agregado canónico de Gestión; las referencias externas no son asociaciones JPA. */
@Entity
@Table(name = "despachos")
@Getter
@Setter
public class Despacho {
    @Id private UUID id;
    @Column(name = "pedido_id", nullable = false) private String idPedido;
    @Column(name = "codigo_operativo") private String codigoOperativo;
    @Column(name = "codigo_rastreo_interno") private String codigoRastreoInterno;
    @Column(name = "zona_id") private UUID idZona;
    @Enumerated(EnumType.STRING) private EstadoDespacho estado;
    @Column(name = "es_simulado") private boolean esSimulacion;
    @Column(name = "destinatario_nombre") private String destinatarioNombre;
    @Column(name = "destinatario_telefono") private String destinatarioTelefono;
    private String direccion;
    private String referencia;
    private String departamento;
    private String provincia;
    private String distrito;
    @Column(name = "codigo_postal") private String codigoPostal;
    private BigDecimal latitud;
    private BigDecimal longitud;
    @Column(name = "peso_kg") private BigDecimal pesoKg;
    @Column(name = "volumen_m3") private BigDecimal volumenM3;
    @Column(name = "cantidad_paquetes") private int cantidadPaquetes = 1;
    @Column(name = "fecha_programada") private LocalDate fechaProgramada;
    @Column(name = "en_cola_desde") private Instant enColaDesde;
    @Column(name = "repartidor_id_externo") private String idRepartidor;
    @Column(name = "asignacion_diaria_id_externa") private String idJornada;
    @Column(name = "reserva_capacidad_id_externa") private String idReserva;
    @Column(name = "secuencia_ruta") private Integer secuenciaRuta;
    @Column(name = "numero_intentos") private int numeroIntentos;
    @Column(name = "observacion_asignacion") private String observacionAsignacion;
    @Version private Integer version = 1;
    @Column(name = "creado_en") private Instant creadoEn;
    @Column(name = "actualizado_en") private Instant actualizadoEn;
}
