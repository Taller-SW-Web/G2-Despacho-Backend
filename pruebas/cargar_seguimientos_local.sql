-- Datos ficticios exclusivos para consultar RT-04 en el PostgreSQL del Compose local.
-- No son pedidos recibidos de Ventas ni sirven para comprobar asignación, incidencias o entregas.
-- es_simulado=false permite probar el contrato público, que excluye los simuladores de F-02.
-- No modifica pedidos existentes, no crea eventos salientes ni llama a otros módulos.
-- Ejecutar únicamente en el contenedor postgres local, contra despacho_gestion.
\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'despacho_gestion' THEN
        RAISE EXCEPTION 'Estos ejemplos requieren la base local despacho_gestion';
    END IF;
END $$;

WITH ejemplos(id, pedido, estados) AS (
    VALUES
      ('94040000-0000-4000-8000-000000000001'::uuid, 'PED-SEG-PENDIENTE', ARRAY['PENDIENTE_ASIGNACION']),
      ('94040000-0000-4000-8000-000000000002'::uuid, 'PED-SEG-ASIGNADO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO']),
      ('94040000-0000-4000-8000-000000000003'::uuid, 'PED-SEG-EN-CAMINO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO','EN_CAMINO']),
      ('94040000-0000-4000-8000-000000000004'::uuid, 'PED-SEG-FALLIDO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO','EN_CAMINO','FALLIDO']),
      ('94040000-0000-4000-8000-000000000005'::uuid, 'PED-SEG-ENTREGADO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO','EN_CAMINO','ENTREGADO']),
      ('94040000-0000-4000-8000-000000000006'::uuid, 'PED-SEG-DEVUELTO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO','EN_CAMINO','FALLIDO','DEVUELTO_A_ORIGEN']),
      ('94040000-0000-4000-8000-000000000007'::uuid, 'PED-SEG-CANCELADO', ARRAY['PENDIENTE_ASIGNACION','CANCELADO']),
      ('94040000-0000-4000-8000-000000000008'::uuid, 'PED-SEG-REPROGRAMADO', ARRAY['PENDIENTE_ASIGNACION','ASIGNADO','EN_CAMINO','FALLIDO','PENDIENTE_ASIGNACION'])
), nuevos AS (
    INSERT INTO despachos(id, pedido_id, codigo_operativo, codigo_rastreo_interno, es_simulado,
      estado, distrito, fecha_programada, cantidad_paquetes, peso_kg, volumen_m3,
      creado_en, actualizado_en)
    SELECT id, pedido, 'DSP-PRUEBA-' || pedido, 'TRK-PRUEBA-' || pedido, false,
      estados[cardinality(estados)], 'San Borja',
      CURRENT_DATE + CASE WHEN pedido = 'PED-SEG-REPROGRAMADO' THEN 2 ELSE 1 END,
      1, 1, 0.01, now() - interval '1 day',
      now() - interval '1 day' + (cardinality(estados) - 1) * interval '30 minutes'
    FROM ejemplos
    ON CONFLICT (pedido_id) DO NOTHING
    RETURNING id
)
INSERT INTO historial_estados_despacho(despacho_id, tipo_operacion, estado_anterior, estado_nuevo,
  funcionalidad_origen, ejecutor_tipo, ejecutor_id, origen, observaciones, datos, registrado_en)
SELECT e.id, 'TRANSICION', e.estados[h.posicion::integer - 1], h.estado,
  'RT-04', 'SERVICIO', 'datos-prueba-local', 'AUTOMATICO',
  'Ejemplo local de lectura RT-04; no representa una operación logística real',
  CASE WHEN h.estado = 'PENDIENTE_ASIGNACION' AND h.posicion > 1
    THEN jsonb_build_object('nuevaFechaProgramada', (CURRENT_DATE + 2)::text)
    ELSE '{}'::jsonb END,
  now() - interval '1 day' + (h.posicion - 1) * interval '30 minutes'
FROM ejemplos e
JOIN nuevos n ON n.id = e.id
CROSS JOIN LATERAL unnest(e.estados) WITH ORDINALITY AS h(estado, posicion);

COMMIT;

SELECT pedido_id, estado FROM despachos
WHERE id IN (
  '94040000-0000-4000-8000-000000000001', '94040000-0000-4000-8000-000000000002',
  '94040000-0000-4000-8000-000000000003', '94040000-0000-4000-8000-000000000004',
  '94040000-0000-4000-8000-000000000005', '94040000-0000-4000-8000-000000000006',
  '94040000-0000-4000-8000-000000000007', '94040000-0000-4000-8000-000000000008'
)
ORDER BY pedido_id;
