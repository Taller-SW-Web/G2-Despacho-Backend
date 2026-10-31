CREATE TABLE cancelaciones_despacho (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    ejecutor_id text NOT NULL,
    clave uuid NOT NULL,
    solicitud text NOT NULL,
    intencion jsonb NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE', 'CONFIRMADA')),
    resultado jsonb,
    procesando_hasta timestamptz,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ejecutor_id, clave),
    CHECK ((estado = 'CONFIRMADA') = (resultado IS NOT NULL))
);
-- El vencimiento del arrendamiento permite recuperar trabajo, nunca retirar el cerco.
CREATE UNIQUE INDEX uq_cancelacion_pendiente ON cancelaciones_despacho(despacho_id) WHERE estado = 'PENDIENTE';
CREATE INDEX idx_cancelaciones_recuperables ON cancelaciones_despacho(proximo_intento_en) WHERE estado = 'PENDIENTE';
