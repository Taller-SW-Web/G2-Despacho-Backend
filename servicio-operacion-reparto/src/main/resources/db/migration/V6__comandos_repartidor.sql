-- F-03: idempotencia de los comandos de campo (inicio de traslado, entrega, fallo y cierre).
-- La clave pertenece al usuario autenticado; la respuesta original se devuelve ante un reintento.
CREATE TABLE comandos_repartidor (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL,
    clave uuid NOT NULL,
    operacion varchar(30) NOT NULL CHECK (operacion IN ('INICIO_TRASLADO', 'ENTREGA', 'FALLO', 'CIERRE_JORNADA')),
    huella varchar(64) NOT NULL,
    estado varchar(12) NOT NULL CHECK (estado IN ('EN_PROCESO', 'COMPLETADO')),
    respuesta jsonb,
    creado_en timestamptz NOT NULL DEFAULT now(),
    completado_en timestamptz,
    CONSTRAINT uq_comandos_repartidor_clave UNIQUE (usuario_id, clave),
    CONSTRAINT ck_comandos_repartidor_respuesta CHECK ((estado = 'EN_PROCESO' AND respuesta IS NULL AND completado_en IS NULL)
        OR (estado = 'COMPLETADO' AND respuesta IS NOT NULL AND completado_en IS NOT NULL))
);

-- Igual que V2: el acceso es solo del backend, nunca de los roles HTTP de Supabase.
DO $$
DECLARE rol text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON TABLE public.comandos_repartidor FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE comandos_repartidor FROM PUBLIC;
