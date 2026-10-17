-- F-02: conservar las migraciones anteriores y extender el agregado canónico.
-- Los identificadores externos son opacos, no necesariamente UUID.
ALTER TABLE despachos ALTER COLUMN pedido_id TYPE text USING pedido_id::text;
ALTER TABLE despachos ALTER COLUMN repartidor_id_externo TYPE text USING repartidor_id_externo::text;
ALTER TABLE despachos ALTER COLUMN asignacion_diaria_id_externa TYPE text USING asignacion_diaria_id_externa::text;
ALTER TABLE despachos ALTER COLUMN reserva_capacidad_id_externa TYPE text USING reserva_capacidad_id_externa::text;
ALTER TABLE intentos_entrega ALTER COLUMN repartidor_id_externo TYPE text USING repartidor_id_externo::text;
ALTER TABLE intentos_entrega ALTER COLUMN asignacion_diaria_id_externa TYPE text USING asignacion_diaria_id_externa::text;
ALTER TABLE intentos_entrega ALTER COLUMN reserva_capacidad_id_externa TYPE text USING reserva_capacidad_id_externa::text;
ALTER TABLE despachos ALTER COLUMN peso_kg TYPE numeric;
ALTER TABLE despachos ALTER COLUMN volumen_m3 TYPE numeric;
ALTER TABLE despachos ADD COLUMN codigo_operativo text;
ALTER TABLE despachos ADD COLUMN departamento text;
ALTER TABLE despachos ADD COLUMN provincia text;
ALTER TABLE despachos ADD COLUMN observacion_asignacion text;
CREATE SEQUENCE secuencia_codigos_despacho;
UPDATE despachos SET codigo_operativo = 'DSP-' || id::text WHERE codigo_operativo IS NULL;
CREATE UNIQUE INDEX uq_despachos_codigo_operativo ON despachos(codigo_operativo);
CREATE UNIQUE INDEX uq_despachos_pedido ON despachos(pedido_id);
CREATE INDEX idx_despachos_pendientes ON despachos(fecha_programada, creado_en, id)
    WHERE estado = 'PENDIENTE_ASIGNACION';
CREATE UNIQUE INDEX uq_despachos_posicion_jornada ON despachos(asignacion_diaria_id_externa, secuencia_ruta)
    WHERE asignacion_diaria_id_externa IS NOT NULL AND secuencia_ruta IS NOT NULL;

CREATE TABLE historial_estados_despacho (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    tipo_operacion text NOT NULL CHECK (tipo_operacion IN ('TRANSICION', 'REASIGNACION', 'REORDENAMIENTO', 'RECEPCION_EN_CENTRO', 'ANULACION_REGISTRADA')),
    estado_anterior text,
    estado_nuevo text NOT NULL,
    funcionalidad_origen text NOT NULL CHECK (funcionalidad_origen IN ('F-02', 'F-03', 'F-04')),
    ejecutor_tipo text NOT NULL CHECK (ejecutor_tipo IN ('USUARIO', 'SERVICIO', 'SISTEMA')),
    ejecutor_id text,
    origen text NOT NULL CHECK (origen IN ('MANUAL', 'AUTOMATICO')),
    motivo_codigo text REFERENCES motivos_fallo(codigo),
    observaciones text,
    datos jsonb NOT NULL DEFAULT '{}',
    registrado_en timestamptz NOT NULL
);
CREATE INDEX idx_historial_despacho ON historial_estados_despacho(despacho_id, registrado_en, id);

CREATE TABLE claves_idempotencia (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    clave text NOT NULL,
    actor_tipo text NOT NULL,
    actor_id text NOT NULL,
    operacion text NOT NULL,
    huella_solicitud text NOT NULL,
    estado text NOT NULL CHECK (estado IN ('EN_PROCESO', 'COMPLETADA', 'FALLIDA')),
    codigo_respuesta integer,
    respuesta jsonb,
    creado_en timestamptz NOT NULL DEFAULT now(),
    expira_en timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_tipo, actor_id, operacion, clave)
);
CREATE INDEX idx_claves_idempotencia_expira ON claves_idempotencia(expira_en);

-- Intención durable: una respuesta perdida de Operación conserva la misma clave.
CREATE TABLE asignaciones_despacho (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    version_origen integer NOT NULL,
    ejecutor_id text NOT NULL,
    id_repartidor text NOT NULL,
    id_jornada text NOT NULL,
    observacion text,
    peso_requerido numeric NOT NULL CHECK (peso_requerido > 0),
    volumen_requerido numeric NOT NULL CHECK (volumen_requerido > 0),
    paquetes_requeridos integer NOT NULL CHECK (paquetes_requeridos > 0),
    estado text NOT NULL CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'RECHAZADA', 'COMPENSACION_PENDIENTE', 'COMPENSADA')),
    id_reserva text,
    respuesta jsonb,
    procesando_hasta timestamptz,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_asignacion_version_activa ON asignaciones_despacho(despacho_id, version_origen)
    WHERE estado NOT IN ('RECHAZADA', 'COMPENSADA');
CREATE INDEX idx_asignaciones_recuperables ON asignaciones_despacho(estado, proximo_intento_en);

CREATE TABLE eventos_salientes (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    destino text NOT NULL,
    tipo_evento text NOT NULL,
    payload jsonb NOT NULL,
    estado_envio text NOT NULL DEFAULT 'PENDIENTE' CHECK (estado_envio IN ('PENDIENTE', 'ENVIADO', 'ENVIO_FALLIDO')),
    intentos_envio integer NOT NULL DEFAULT 0,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    bloqueada_hasta timestamptz,
    bloqueo_id uuid,
    ultimo_error text,
    creado_en timestamptz NOT NULL DEFAULT now(),
    enviado_en timestamptz,
    actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_eventos_salientes_pendientes ON eventos_salientes(estado_envio, proximo_intento_en);
CREATE INDEX idx_eventos_salientes_despacho ON eventos_salientes(despacho_id);
