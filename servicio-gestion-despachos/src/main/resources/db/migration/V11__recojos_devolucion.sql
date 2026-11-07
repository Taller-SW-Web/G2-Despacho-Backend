-- ES-F03-09, ES-F02-09 y ES-F04-07: el recojo es una operación aparte; el despacho original sigue ENTREGADO.
ALTER TABLE motivos_fallo ADD COLUMN ambito text NOT NULL DEFAULT 'ENTREGA'
    CONSTRAINT ck_motivos_fallo_ambito CHECK (ambito IN ('ENTREGA', 'RECOJO', 'AMBOS'));
UPDATE motivos_fallo SET ambito = 'AMBOS'
    WHERE codigo IN ('CLIENTE_AUSENTE', 'DIRECCION_NO_UBICADA', 'DATOS_DE_CONTACTO_ERRONEOS', 'ZONA_INACCESIBLE', 'NO_INTENTADO');
INSERT INTO motivos_fallo (codigo, etiqueta, seleccionable_en_campo, consume_intento, ambito) VALUES
    ('CLIENTE_NO_ENTREGA_PRODUCTO', 'El cliente no entrega el producto', true, true, 'RECOJO'),
    ('PRODUCTO_NO_CORRESPONDE', 'El producto no corresponde', true, true, 'RECOJO')
ON CONFLICT (codigo) DO NOTHING;

CREATE TABLE recojos (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    codigo_rastreo_interno text NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE_ASIGNACION', 'ASIGNADO', 'EN_CAMINO', 'RECOGIDO', 'FALLIDO',
        'RECIBIDO_EN_CENTRO', 'CERRADO_SIN_RECOJO', 'CANCELADO')),
    es_simulado boolean NOT NULL,
    contacto_nombre text NOT NULL,
    contacto_telefono text NOT NULL,
    direccion text NOT NULL,
    referencia text,
    departamento text,
    provincia text,
    distrito text NOT NULL,
    codigo_postal text,
    zona_id uuid,
    lineas jsonb NOT NULL,
    peso_kg numeric NOT NULL CHECK (peso_kg > 0),
    volumen_m3 numeric NOT NULL CHECK (volumen_m3 > 0),
    cantidad_paquetes integer NOT NULL CHECK (cantidad_paquetes > 0),
    fecha_programada date NOT NULL,
    repartidor_id_externo text,
    asignacion_diaria_id_externa text,
    reserva_capacidad_id_externa text,
    secuencia_ruta integer CHECK (secuencia_ruta > 0),
    numero_intentos integer NOT NULL DEFAULT 0,
    motivo_codigo text REFERENCES motivos_fallo(codigo),
    evidencia_id_externa uuid,
    recibido_en_centro_en timestamptz,
    recibido_por text,
    motivo_cierre text,
    version integer NOT NULL DEFAULT 1,
    creado_en timestamptz NOT NULL,
    actualizado_en timestamptz NOT NULL
);
-- Un despacho tiene como máximo un recojo activo.
CREATE UNIQUE INDEX uq_recojo_activo_despacho ON recojos(despacho_id)
    WHERE estado NOT IN ('RECIBIDO_EN_CENTRO', 'CERRADO_SIN_RECOJO', 'CANCELADO');
CREATE INDEX idx_recojos_estado ON recojos(estado, fecha_programada, creado_en);
CREATE INDEX idx_recojos_jornada ON recojos(asignacion_diaria_id_externa);

CREATE TABLE historial_estados_recojo (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recojo_id uuid NOT NULL REFERENCES recojos(id),
    estado_anterior text,
    estado_nuevo text NOT NULL,
    funcionalidad_origen text NOT NULL,
    ejecutor_tipo text NOT NULL,
    ejecutor_id text NOT NULL,
    origen text NOT NULL,
    motivo_codigo text,
    observaciones text,
    datos jsonb NOT NULL DEFAULT '{}'::jsonb,
    registrado_en timestamptz NOT NULL
);
CREATE INDEX idx_historial_recojo ON historial_estados_recojo(recojo_id, registrado_en);

-- Intención durable: la reserva ocurre fuera de la transacción y se recupera o compensa si algo falla.
CREATE TABLE asignaciones_recojo (
    id uuid PRIMARY KEY,
    recojo_id uuid NOT NULL REFERENCES recojos(id),
    clave uuid NOT NULL,
    version_origen integer NOT NULL,
    ejecutor_id text NOT NULL,
    id_repartidor text NOT NULL,
    id_jornada text NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'RECHAZADA', 'COMPENSACION_PENDIENTE', 'COMPENSADA')),
    id_reserva text,
    respuesta jsonb,
    procesando_hasta timestamptz,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_asignacion_recojo_clave ON asignaciones_recojo(recojo_id, clave) WHERE estado <> 'RECHAZADA';
CREATE INDEX idx_asignaciones_recojo_recuperables ON asignaciones_recojo(proximo_intento_en) WHERE estado IN ('PENDIENTE', 'COMPENSACION_PENDIENTE');

ALTER TABLE eventos_salientes ADD COLUMN recojo_id uuid REFERENCES recojos(id);

-- Igual que las demás tablas del servicio: el acceso es solo del backend.
DO $$
DECLARE rol text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON TABLE public.recojos, public.historial_estados_recojo, public.asignaciones_recojo FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE recojos, historial_estados_recojo, asignaciones_recojo FROM PUBLIC;
