-- Complemento de F03 para el esquema heredado que ya recibió F05 y F06.
-- No volver a ejecutar F05/F06 ni activar Flyway sobre ese esquema.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE public.proyeccion_despacho
    ADD COLUMN IF NOT EXISTS maximo_intentos integer
    CHECK (maximo_intentos IS NULL OR maximo_intentos > 0);

-- La vista existente conserva el orden de sus columnas y sus permisos.
CREATE OR REPLACE VIEW public.proyecciones_despacho
    WITH (security_invoker = true) AS SELECT * FROM public.proyeccion_despacho;

COMMIT;
