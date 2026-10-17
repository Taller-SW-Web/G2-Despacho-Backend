-- F-05: extensiones aditivas del esquema canónico V1. No ejecutar V1 sobre un esquema heredado.
CREATE VIEW perfiles_flota WITH (security_invoker = true) AS SELECT * FROM repartidores;

CREATE TABLE reservas_capacidad (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), clave_asignacion uuid NOT NULL UNIQUE,
    despacho_id_externo uuid NOT NULL, asignacion_diaria_id uuid NOT NULL REFERENCES asignaciones_diarias(id),
    peso_kg numeric(10,3) NOT NULL CHECK (peso_kg > 0),
    volumen_m3 numeric(10,4) NOT NULL CHECK (volumen_m3 > 0),
    cantidad_paquetes smallint NOT NULL CHECK (cantidad_paquetes > 0),
    estado varchar(10) NOT NULL CHECK (estado IN ('ACTIVA', 'LIBERADA')), motivo_liberacion text,
    creado_en timestamptz NOT NULL DEFAULT now(), liberado_en timestamptz, version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_reserva_despacho_activa ON reservas_capacidad(despacho_id_externo) WHERE estado = 'ACTIVA';

CREATE TABLE proyecciones_despacho (
    despacho_id_externo uuid PRIMARY KEY, id_pedido varchar(50), codigo_rastreo_interno varchar(20),
    asignacion_diaria_id uuid NOT NULL REFERENCES asignaciones_diarias(id),
    reserva_capacidad_id uuid UNIQUE REFERENCES reservas_capacidad(id), secuencia_ruta smallint,
    estado varchar(25) NOT NULL, destinatario_nombre text, destinatario_telefono text,
    direccion text, referencia text, distrito text, fecha_programada date,
    numero_intentos integer NOT NULL DEFAULT 0, version integer NOT NULL DEFAULT 0,
    actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE VIEW despachos_flota WITH (security_invoker = true) AS SELECT asignacion_diaria_id, estado FROM proyecciones_despacho;
CREATE OR REPLACE FUNCTION public.bloquear_operacion_flota() RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('lock_timeout', '10s', true);
    LOCK TABLE asignaciones_diarias, reservas_capacidad, proyecciones_despacho IN SHARE ROW EXCLUSIVE MODE;
    RETURN true;
END $$;
-- Objetos comunes; ejecutar dentro de una transacción y después de preparar las vistas.
CREATE UNIQUE INDEX IF NOT EXISTS uq_flota_correo_normalizado ON repartidores (lower(btrim(correo)));
CREATE UNIQUE INDEX IF NOT EXISTS uq_flota_dni_normalizado ON repartidores (btrim(dni));
CREATE TABLE IF NOT EXISTS auditoria_configuracion (
    id uuid PRIMARY KEY, entidad text NOT NULL, entidad_id text NOT NULL, accion text NOT NULL,
    datos_anteriores jsonb, datos_nuevos jsonb, usuario_id uuid NOT NULL,
    registrado_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now(), actualizado_en timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION public.validar_jornada_flota() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado = 'ACTIVA' THEN
        PERFORM 1 FROM perfiles_flota WHERE id = NEW.repartidor_id
            AND estado_registro = 'ACTIVO' AND estado_vinculacion = 'VINCULADO' FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Repartidor no habilitado' USING ERRCODE = '23514'; END IF;
        PERFORM 1 FROM furgonetas WHERE id = NEW.furgoneta_id AND estado = 'DISPONIBLE' FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Furgoneta no disponible' USING ERRCODE = '23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validar_jornada_flota BEFORE INSERT OR UPDATE OF estado, repartidor_id, furgoneta_id
    ON asignaciones_diarias FOR EACH ROW EXECUTE FUNCTION public.validar_jornada_flota();

CREATE OR REPLACE FUNCTION public.validar_carga_flota() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado IN ('ACTIVA', 'ASIGNADO', 'EN_CAMINO') THEN
        PERFORM 1 FROM asignaciones_diarias WHERE id = NEW.asignacion_diaria_id AND estado = 'ACTIVA' FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'La jornada no admite nueva carga' USING ERRCODE = '23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validar_reserva_flota BEFORE INSERT OR UPDATE OF estado, asignacion_diaria_id
    ON reservas_capacidad FOR EACH ROW EXECUTE FUNCTION public.validar_carga_flota();
CREATE TRIGGER trg_validar_proyeccion_flota BEFORE INSERT OR UPDATE OF estado, asignacion_diaria_id
    ON proyecciones_despacho FOR EACH ROW EXECUTE FUNCTION public.validar_carga_flota();
CREATE UNIQUE INDEX uq_flota_placa_normalizada ON furgonetas (upper(btrim(placa)));
ALTER TABLE repartidores ADD CONSTRAINT ck_flota_vinculacion CHECK (
    (estado_vinculacion = 'PENDIENTE' AND id_usuario_seguridad IS NULL)
    OR (estado_vinculacion = 'VINCULADO' AND id_usuario_seguridad IS NOT NULL));
-- Las vistas no deben ampliar el acceso de los roles HTTP de Supabase.
DO $$
DECLARE rol text; objeto text;
BEGIN
    FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        FOREACH objeto IN ARRAY ARRAY['perfiles_flota', 'despachos_flota', 'furgonetas', 'auditoria_configuracion'] LOOP
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %I', objeto, rol);
        END LOOP;
        EXECUTE format('REVOKE ALL ON FUNCTION public.bloquear_operacion_flota() FROM %I', rol);
    END LOOP;
END $$;
REVOKE ALL ON TABLE perfiles_flota, despachos_flota, auditoria_configuracion FROM PUBLIC;
REVOKE ALL ON FUNCTION public.bloquear_operacion_flota() FROM PUBLIC;
