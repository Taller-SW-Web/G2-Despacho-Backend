-- ES-F06-09 y ES-F03-09: cada jornada es de entregas o de recojos; las existentes quedan como entregas.
ALTER TABLE asignaciones_diarias
    ADD COLUMN tipo_operacion varchar(10) NOT NULL DEFAULT 'ENTREGAS'
    CONSTRAINT ck_asignaciones_tipo_operacion CHECK (tipo_operacion IN ('ENTREGAS', 'RECOJOS'));

-- La reserva conserva el despacho original; la regla de una reserva activa por despacho sigue vigente.
ALTER TABLE reservas_capacidad ADD COLUMN recojo_id_externo uuid;

CREATE TABLE proyecciones_recojo (
    recojo_id_externo uuid PRIMARY KEY,
    despacho_id_externo uuid NOT NULL,
    asignacion_diaria_id uuid NOT NULL REFERENCES asignaciones_diarias(id),
    reserva_capacidad_id uuid UNIQUE REFERENCES reservas_capacidad(id),
    secuencia_ruta smallint,
    estado varchar(25) NOT NULL,
    version bigint NOT NULL,
    payload_proyeccion jsonb NOT NULL,
    actualizado_en timestamptz NOT NULL,
    actualizado_por text
);
CREATE INDEX idx_proyecciones_recojo_jornada ON proyecciones_recojo(asignacion_diaria_id, estado);

ALTER TABLE evidencias ADD COLUMN recojo_id_externo uuid;
ALTER TABLE evidencias ALTER COLUMN tipo TYPE varchar(15);
ALTER TABLE evidencias DROP CONSTRAINT evidencias_tipo_check;
ALTER TABLE evidencias ADD CONSTRAINT evidencias_tipo_check CHECK (tipo IN ('ENTREGA', 'FALLO', 'RECOJO', 'FALLO_RECOJO'));
ALTER TABLE evidencias ADD CONSTRAINT ck_evidencias_recojo CHECK ((tipo IN ('RECOJO', 'FALLO_RECOJO')) = (recojo_id_externo IS NOT NULL));

ALTER TABLE comandos_repartidor DROP CONSTRAINT comandos_repartidor_operacion_check;
ALTER TABLE comandos_repartidor ADD CONSTRAINT comandos_repartidor_operacion_check CHECK (operacion IN (
    'INICIO_TRASLADO', 'ENTREGA', 'FALLO', 'CIERRE_JORNADA', 'INICIO_RECOJO', 'RECOJO', 'FALLO_RECOJO'));

-- F-05 no puede cambiar recursos con recojos en curso.
CREATE OR REPLACE VIEW despachos_flota WITH (security_invoker = true) AS
    SELECT asignacion_diaria_id, estado FROM proyecciones_despacho
    UNION ALL SELECT asignacion_diaria_id, estado FROM proyecciones_recojo;

-- El bloqueo común debe cubrir también la tabla nueva para serializar reservas, cierres y proyecciones.
CREATE OR REPLACE FUNCTION public.bloquear_operacion_flota() RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('lock_timeout', '10s', true);
    LOCK TABLE asignaciones_diarias, reservas_capacidad, proyecciones_despacho, proyecciones_recojo IN SHARE ROW EXCLUSIVE MODE;
    RETURN true;
END $$;

-- Igual que V2 y V6: el acceso es solo del backend, nunca de los roles HTTP de Supabase.
DO $$
DECLARE rol text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON TABLE public.proyecciones_recojo FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE proyecciones_recojo FROM PUBLIC;
