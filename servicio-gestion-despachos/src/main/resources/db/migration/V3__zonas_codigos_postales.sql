-- En Supabase esta tabla ya fue creada manualmente. Esta migracion reproduce
-- su estructura para bases nuevas y Testcontainers; no altera tablas existentes.
CREATE TABLE zonas_codigos_postales (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    zona_id         uuid NOT NULL REFERENCES zonas (id) ON DELETE CASCADE,
    distrito_id     uuid NOT NULL REFERENCES distrito (id),
    codigo_postal   text NOT NULL,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    actualizado_en  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_zcp UNIQUE (zona_id, distrito_id, codigo_postal)
);
CREATE INDEX idx_zcp_zona ON zonas_codigos_postales (zona_id);
CREATE INDEX idx_zcp_cp ON zonas_codigos_postales (codigo_postal);
CREATE TRIGGER trg_zcp_actualizado_en BEFORE UPDATE ON zonas_codigos_postales
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
