-- Mensajería interna: el eventId se registra antes de ejecutar el efecto de dominio.
-- Una reentrega del broker se descarta sin repetir la liberación ni la proyección.
CREATE TABLE eventos_procesados (
    event_id uuid PRIMARY KEY,
    procesado_en timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE rol text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON TABLE public.eventos_procesados FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE eventos_procesados FROM PUBLIC;
