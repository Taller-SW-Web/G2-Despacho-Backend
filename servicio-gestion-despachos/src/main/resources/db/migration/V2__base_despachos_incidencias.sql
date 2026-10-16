-- Base canónica compartida de Gestión, modelo de datos §§3.3/3.4.
-- ES-F04-01 solo lee estas tablas; los comandos de F-02/F-03 se implementan por separado.
CREATE TABLE motivos_fallo (
    codigo text PRIMARY KEY,
    etiqueta text NOT NULL,
    seleccionable_en_campo boolean NOT NULL DEFAULT true,
    consume_intento boolean NOT NULL DEFAULT true,
    activo boolean NOT NULL DEFAULT true,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now()
);

INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento) VALUES
    ('CLIENTE_AUSENTE', 'Cliente ausente', true, true),
    ('DIRECCION_NO_UBICADA', 'Dirección no localizada', true, true),
    ('RECHAZO_DEL_PAQUETE', 'Cliente rechaza el paquete', true, true),
    ('DATOS_DE_CONTACTO_ERRONEOS', 'Datos de contacto incorrectos', true, true),
    ('ZONA_INACCESIBLE', 'Zona inaccesible o insegura', true, true),
    ('PAQUETE_DANADO', 'Paquete dañado antes de la entrega', true, true),
    ('NO_INTENTADO', 'No intentado por fin de jornada', false, false);

CREATE TABLE despachos (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id uuid NOT NULL,
    zona_id uuid REFERENCES zonas(id),
    codigo_rastreo_interno text UNIQUE,
    es_simulado boolean NOT NULL DEFAULT false,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE_ASIGNACION', 'ASIGNADO', 'EN_CAMINO',
        'ENTREGADO', 'FALLIDO', 'DEVUELTO_A_ORIGEN', 'CANCELADO')),
    destinatario_nombre text,
    destinatario_telefono text,
    direccion text,
    referencia text,
    distrito text,
    codigo_postal text,
    latitud numeric(9,6),
    longitud numeric(9,6),
    peso_kg numeric(10,2) CHECK (peso_kg > 0),
    volumen_m3 numeric(10,3) CHECK (volumen_m3 > 0),
    cantidad_paquetes integer CHECK (cantidad_paquetes = 1),
    fecha_comprometida date,
    fecha_programada date,
    en_cola_desde timestamptz,
    repartidor_id_externo uuid,
    asignacion_diaria_id_externa uuid,
    reserva_capacidad_id_externa uuid,
    secuencia_ruta integer CHECK (secuencia_ruta > 0),
    numero_intentos integer NOT NULL DEFAULT 0 CHECK (numero_intentos >= 0),
    ultima_incidencia_id uuid,
    pedido_anulado_en timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_despachos_pedido ON despachos(pedido_id);
CREATE INDEX idx_despachos_zona ON despachos(zona_id);
CREATE INDEX idx_despachos_estado ON despachos(estado);

CREATE TABLE intentos_entrega (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    asignacion_diaria_id_externa uuid,
    repartidor_id_externo uuid,
    reserva_capacidad_id_externa uuid,
    resultado text NOT NULL CHECK (resultado IN ('ENTREGADO', 'FALLIDO')),
    motivo_codigo text REFERENCES motivos_fallo(codigo),
    consume_intento boolean NOT NULL DEFAULT true,
    comentario text,
    nombre_receptor text,
    evidencia_id_externa uuid,
    origen text,
    registrado_en timestamptz NOT NULL DEFAULT now(),
    recibido_en_centro_en timestamptz,
    recibido_por uuid,
    sello_intacto boolean,
    observacion_recepcion text,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_intentos_entrega_propietario UNIQUE (id, despacho_id)
);
CREATE INDEX idx_intentos_entrega_despacho ON intentos_entrega(despacho_id);
CREATE INDEX idx_intentos_entrega_motivo ON intentos_entrega(motivo_codigo);

-- La referencia vigente solo puede apuntar a una incidencia del mismo despacho.
ALTER TABLE despachos ADD CONSTRAINT fk_despachos_ultima_incidencia
    FOREIGN KEY (ultima_incidencia_id, id) REFERENCES intentos_entrega(id, despacho_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER trg_motivos_fallo_actualizado_en BEFORE UPDATE ON motivos_fallo
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_despachos_actualizado_en BEFORE UPDATE ON despachos
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_intentos_entrega_actualizado_en BEFORE UPDATE ON intentos_entrega
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
