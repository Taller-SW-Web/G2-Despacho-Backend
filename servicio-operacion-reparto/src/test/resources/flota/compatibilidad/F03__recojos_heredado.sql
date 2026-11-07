-- Complemento de ES-F06-09 y ES-F03-09 para el esquema heredado que ya recibió F05, F06 y F03.
-- No activar Flyway sobre ese esquema; este script se aplica una sola vez y de forma revisada.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE public.asignaciones_diarias
    ADD COLUMN IF NOT EXISTS tipo_operacion varchar(10) NOT NULL DEFAULT 'ENTREGAS'
    CHECK (tipo_operacion IN ('ENTREGAS', 'RECOJOS'));
ALTER TABLE public.reservas_capacidad ADD COLUMN IF NOT EXISTS recojo_id_externo uuid;

CREATE TABLE IF NOT EXISTS public.proyecciones_recojo (
    recojo_id_externo uuid PRIMARY KEY,
    despacho_id_externo uuid NOT NULL,
    asignacion_diaria_id uuid NOT NULL REFERENCES public.asignaciones_diarias(id),
    reserva_capacidad_id uuid UNIQUE REFERENCES public.reservas_capacidad(id),
    secuencia_ruta smallint,
    estado varchar(25) NOT NULL,
    version bigint NOT NULL,
    payload_proyeccion jsonb NOT NULL,
    actualizado_en timestamptz NOT NULL,
    actualizado_por text
);
CREATE INDEX IF NOT EXISTS idx_proyecciones_recojo_jornada ON public.proyecciones_recojo(asignacion_diaria_id, estado);

CREATE OR REPLACE VIEW public.despachos_flota WITH (security_invoker = true) AS
    SELECT asignacion_diaria_id, estado FROM public.proyeccion_despacho
    UNION ALL SELECT asignacion_diaria_id, estado FROM public.proyecciones_recojo;

CREATE OR REPLACE FUNCTION public.bloquear_operacion_flota() RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('lock_timeout', '10s', true);
    LOCK TABLE asignaciones_diarias, reservas_capacidad, proyeccion_despacho, proyecciones_recojo IN SHARE ROW EXCLUSIVE MODE;
    RETURN true;
END $$;

DO $$
DECLARE rol text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON TABLE public.proyecciones_recojo FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE public.proyecciones_recojo FROM PUBLIC;

COMMIT;
