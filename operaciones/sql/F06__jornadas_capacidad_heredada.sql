-- AdaptaciÃ³n aditiva F-06 para el esquema heredado que ya recibiÃ³ F05.
-- Probar primero sobre copia aislada. No ejecutar Flyway en Supabase.
-- Si existen duplicados o jornadas activas simultÃ¡neas, falla y revierte; no elimina ni corrige datos automÃ¡ticamente.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
SELECT public.bloquear_operacion_flota();

ALTER TABLE reservas_capacidad ADD COLUMN clave_asignacion uuid;
ALTER TABLE reservas_capacidad ADD COLUMN respuesta_confirmacion jsonb;
ALTER TABLE reservas_capacidad ADD COLUMN registrada_por text;
ALTER TABLE reservas_capacidad ADD COLUMN liberada_por text;
-- Las reservas histÃ³ricas conservan clave y actor desconocidos; las nuevas siempre los guardan.
CREATE UNIQUE INDEX uq_f06_clave_asignacion ON reservas_capacidad(clave_asignacion) WHERE clave_asignacion IS NOT NULL;
CREATE UNIQUE INDEX uq_f06_reserva_despacho ON reservas_capacidad(despacho_id_externo) WHERE estado='ACTIVA';

ALTER TABLE proyeccion_despacho ALTER COLUMN id SET DEFAULT gen_random_uuid();
-- El contrato admite identificadores externos de pedido no UUID. Conservar el UUID histórico,
-- pero permitir que las nuevas proyecciones utilicen exclusivamente id_pedido.
ALTER TABLE proyeccion_despacho ALTER COLUMN pedido_id DROP NOT NULL;
ALTER TABLE proyeccion_despacho ADD COLUMN id_pedido varchar(50);
UPDATE proyeccion_despacho SET id_pedido=pedido_id::text WHERE pedido_id IS NOT NULL;
ALTER TABLE proyeccion_despacho ADD COLUMN direccion text;
ALTER TABLE proyeccion_despacho ADD COLUMN referencia text;
ALTER TABLE proyeccion_despacho ADD COLUMN distrito text;
ALTER TABLE proyeccion_despacho ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE proyeccion_despacho ADD COLUMN payload_proyeccion jsonb;
ALTER TABLE proyeccion_despacho ADD COLUMN actualizado_por text;
CREATE UNIQUE INDEX uq_f06_proyeccion_despacho ON proyeccion_despacho(despacho_id_externo);
CREATE UNIQUE INDEX uq_f06_proyeccion_reserva ON proyeccion_despacho(reserva_capacidad_id) WHERE reserva_capacidad_id IS NOT NULL;
-- Puede existir la vista histÃ³rica SELECT * usada por otros consumidores.
-- Conservar sus columnas en el mismo orden y agregar las nuevas al final mantiene sus dependencias.
CREATE OR REPLACE VIEW proyecciones_despacho WITH (security_invoker=true) AS SELECT * FROM proyeccion_despacho;

CREATE UNIQUE INDEX uq_f06_repartidor_activo ON asignaciones_diarias(repartidor_id) WHERE estado='ACTIVA';
CREATE UNIQUE INDEX uq_f06_furgoneta_activa ON asignaciones_diarias(furgoneta_id) WHERE estado='ACTIVA';
CREATE INDEX idx_f06_jornadas_fecha ON asignaciones_diarias(fecha_jornada,repartidor_id,abierta_en DESC);
CREATE INDEX idx_f06_reservas_ocupacion ON reservas_capacidad(asignacion_diaria_id) WHERE estado='ACTIVA';
CREATE INDEX idx_f06_proyecciones_jornada ON proyeccion_despacho(asignacion_diaria_id,estado);
REVOKE ALL ON TABLE proyecciones_despacho FROM PUBLIC;
DO $$
DECLARE rol text;
BEGIN
  FOR rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated') LOOP
    EXECUTE format('REVOKE ALL ON TABLE public.proyecciones_despacho FROM %I',rol);
  END LOOP;
END $$;
COMMIT;
