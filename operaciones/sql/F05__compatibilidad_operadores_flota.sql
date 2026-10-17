-- Adaptación F-05 del esquema heredado observado en Operadores y Flota.
-- NO contiene credenciales. No renombra, elimina ni copia tablas o filas.
-- Ejecutar UNA vez con ON_ERROR_STOP y una conexión de sesión; conservar Flyway desactivado.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
LOCK TABLE repartidores, furgoneta, asignaciones_diarias, reservas_capacidad, proyeccion_despacho
    IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE repartidores ADD COLUMN estado_registro varchar(10) NOT NULL DEFAULT 'ACTIVO'
    CHECK (estado_registro IN ('ACTIVO', 'INACTIVO'));
ALTER TABLE repartidores ADD COLUMN vinculado_en timestamptz;
ALTER TABLE asignaciones_diarias ADD COLUMN cerrada_en timestamptz;
ALTER TABLE asignaciones_diarias ADD COLUMN tipo_cierre varchar(25)
    CHECK (tipo_cierre IN ('REPARTIDOR', 'CORTE_AUTOMATICO', 'GESTOR_DESPACHO', 'MANTENIMIENTO'));
-- No se inventa una fecha histórica de cierre para jornadas ya cerradas.
CREATE VIEW perfiles_flota WITH (security_invoker = true) AS SELECT id, nombres, apellidos, dni, telefono, correo, numero_brevete,
    turno_habitual, estado_registro, usuario_seguridad_id AS id_usuario_seguridad,
    estado_vinculacion, vinculado_en, creado_en, actualizado_en, creado_por, actualizado_por FROM repartidores;
CREATE VIEW furgonetas WITH (security_invoker = true) AS SELECT * FROM furgoneta;
CREATE VIEW despachos_flota WITH (security_invoker = true) AS SELECT asignacion_diaria_id, estado FROM proyeccion_despacho;

CREATE UNIQUE INDEX uq_flota_usuario_seguridad ON repartidores(usuario_seguridad_id);
CREATE UNIQUE INDEX uq_flota_placa_normalizada ON furgoneta (upper(btrim(placa)));
ALTER TABLE repartidores ADD CONSTRAINT ck_flota_vinculacion CHECK (
    (estado_vinculacion = 'PENDIENTE' AND usuario_seguridad_id IS NULL)
    OR (estado_vinculacion = 'VINCULADO' AND usuario_seguridad_id IS NOT NULL));
ALTER TABLE repartidores ADD CONSTRAINT ck_flota_turno CHECK (turno_habitual IN ('MANANA', 'TARDE', 'COMPLETO'));
ALTER TABLE furgoneta ADD CONSTRAINT ck_flota_estado CHECK (estado IN ('DISPONIBLE', 'EN_MANTENIMIENTO', 'INACTIVA'));
ALTER TABLE furgoneta ADD CONSTRAINT ck_flota_capacidad CHECK
    (capacidad_max_kg > 0 AND capacidad_max_m3 > 0 AND max_paquetes BETWEEN 1 AND 32767);
CREATE UNIQUE INDEX uq_jornada_repartidor_activo ON asignaciones_diarias(repartidor_id, fecha_jornada) WHERE estado = 'ACTIVA';
CREATE UNIQUE INDEX uq_jornada_furgoneta_activa ON asignaciones_diarias(furgoneta_id, fecha_jornada) WHERE estado = 'ACTIVA';
CREATE OR REPLACE FUNCTION public.bloquear_operacion_flota() RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('lock_timeout', '10s', true);
    LOCK TABLE asignaciones_diarias, reservas_capacidad, proyeccion_despacho IN SHARE ROW EXCLUSIVE MODE;
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
    ON proyeccion_despacho FOR EACH ROW EXECUTE FUNCTION public.validar_carga_flota();
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

COMMIT;
