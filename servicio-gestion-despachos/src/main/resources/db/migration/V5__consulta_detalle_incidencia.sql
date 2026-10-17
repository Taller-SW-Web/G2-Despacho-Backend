-- Fuente durable de recepción reutilizable por ES-F04-03. Esta migración no genera recepciones ni eventos.
CREATE TABLE liberaciones_capacidad (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    incidencia_id uuid NOT NULL UNIQUE,
    reserva_id_externa text NOT NULL UNIQUE,
    jornada_id_externa text NOT NULL,
    solicitada_por text NOT NULL,
    version_solicitada integer NOT NULL CHECK (version_solicitada > 0),
    hash_contenido text NOT NULL,
    respuesta_original jsonb NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE', 'CONFIRMADA')),
    requiere_revision boolean NOT NULL DEFAULT false,
    intentos_envio integer NOT NULL DEFAULT 0 CHECK (intentos_envio >= 0),
    proximo_intento_en timestamptz,
    ultimo_intento_en timestamptz,
    confirmada_en timestamptz,
    ultimo_codigo_error text,
    bloqueada_hasta timestamptz,
    token_procesamiento uuid,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (incidencia_id, despacho_id) REFERENCES intentos_entrega(id, despacho_id),
    CHECK ((estado = 'PENDIENTE' AND confirmada_en IS NULL AND proximo_intento_en IS NOT NULL)
        OR (estado = 'CONFIRMADA' AND confirmada_en IS NOT NULL AND NOT requiere_revision AND proximo_intento_en IS NULL))
);
CREATE INDEX idx_liberaciones_pendientes ON liberaciones_capacidad(estado, proximo_intento_en, bloqueada_hasta);
CREATE TRIGGER trg_liberaciones_actualizado_en BEFORE UPDATE ON liberaciones_capacidad
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
