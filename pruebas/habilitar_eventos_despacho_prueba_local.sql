-- Habilita los eventos hacia Ventas para UN despacho de prueba del Compose local.
-- Los despachos generados en Programación (PED-SIM-...) son simulados y, por contrato, no
-- notifican a Ventas. Crear uno real exige Productos (§7.1), que no existe en local.
-- Este script marca un despacho de prueba como no simulado mientras sigue PENDIENTE_ASIGNACION,
-- de modo que la asignación y las acciones de la app del repartidor generen los eventos
-- DESPACHO_* y lleguen al doble ventas-local.
-- Ejecutar únicamente en el contenedor postgres local, contra despacho_gestion:
--   psql -v pedido=PED-SIM-XXXX ...
\set ON_ERROR_STOP on
BEGIN;

SELECT set_config('despacho_prueba.pedido', :'pedido', false);

DO $$
DECLARE
    pedido text := current_setting('despacho_prueba.pedido');
    fila record;
BEGIN
    IF current_database() <> 'despacho_gestion' THEN
        RAISE EXCEPTION 'Este script requiere la base local despacho_gestion';
    END IF;
    IF pedido NOT LIKE 'PED-SIM-%' THEN
        RAISE EXCEPTION 'Solo se aceptan pedidos de prueba PED-SIM-...';
    END IF;
    SELECT id, estado, es_simulado INTO fila FROM despachos WHERE pedido_id = pedido FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No existe un despacho para %', pedido;
    END IF;
    IF NOT fila.es_simulado THEN
        RAISE NOTICE 'El despacho de % ya genera eventos', pedido;
        RETURN;
    END IF;
    IF fila.estado <> 'PENDIENTE_ASIGNACION' THEN
        RAISE EXCEPTION 'El despacho debe seguir PENDIENTE_ASIGNACION (está %)', fila.estado;
    END IF;
    UPDATE despachos SET es_simulado = false, actualizado_en = now() WHERE id = fila.id;
END $$;

COMMIT;

SELECT pedido_id, estado, es_simulado FROM despachos
WHERE pedido_id = current_setting('despacho_prueba.pedido', true);
