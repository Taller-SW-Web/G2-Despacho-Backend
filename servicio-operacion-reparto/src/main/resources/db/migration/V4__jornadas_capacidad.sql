-- F-06: respuestas durables e índices para jornadas, ocupación y proyecciones.
-- No modifica el historial ni asigna identidades a escrituras históricas desconocidas.
ALTER TABLE reservas_capacidad ADD COLUMN respuesta_confirmacion jsonb;
ALTER TABLE reservas_capacidad ADD COLUMN registrada_por text;
ALTER TABLE reservas_capacidad ADD COLUMN liberada_por text;
ALTER TABLE proyecciones_despacho ADD COLUMN payload_proyeccion jsonb;
ALTER TABLE proyecciones_despacho ADD COLUMN actualizado_por text;
ALTER TABLE proyecciones_despacho ALTER COLUMN version TYPE bigint;

CREATE UNIQUE INDEX uq_f06_repartidor_activo ON asignaciones_diarias(repartidor_id) WHERE estado='ACTIVA';
CREATE UNIQUE INDEX uq_f06_furgoneta_activa ON asignaciones_diarias(furgoneta_id) WHERE estado='ACTIVA';
CREATE INDEX idx_f06_jornadas_fecha ON asignaciones_diarias(fecha_jornada,repartidor_id,abierta_en DESC);
CREATE INDEX idx_f06_reservas_ocupacion ON reservas_capacidad(asignacion_diaria_id) WHERE estado='ACTIVA';
CREATE INDEX idx_f06_proyecciones_jornada ON proyecciones_despacho(asignacion_diaria_id,estado);
