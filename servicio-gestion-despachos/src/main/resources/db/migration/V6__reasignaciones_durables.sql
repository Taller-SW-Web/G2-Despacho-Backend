CREATE TABLE reasignaciones_despacho (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    ejecutor_id text NOT NULL,
    clave uuid NOT NULL,
    solicitud text NOT NULL,
    intencion jsonb NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE','CONFIRMADA','RECHAZADA')),
    respuesta jsonb,
    rechazo jsonb,
    procesando_hasta timestamptz,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ejecutor_id, clave)
);
-- El cerco dura hasta confirmar localmente o recibir un rechazo remoto definitivo.
CREATE UNIQUE INDEX uq_reasignacion_pendiente ON reasignaciones_despacho(despacho_id) WHERE estado='PENDIENTE';
CREATE INDEX idx_reasignaciones_recuperables ON reasignaciones_despacho(proximo_intento_en) WHERE estado='PENDIENTE';
