-- ES-F02-07: la anulación de Ventas se audita como ANULACION_REGISTRADA (contrato §5.3).
-- Normaliza las filas escritas con el nombre anterior; no cambia estados, motivos ni datos.
UPDATE public.historial_estados_despacho
SET tipo_operacion = 'ANULACION_REGISTRADA'
WHERE tipo_operacion = 'CANCELACION_POR_ANULACION';
