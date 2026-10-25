-- ES-F04-05: resultado durable en el historial existente, sin tabla de cierres.
-- Los históricos se conservan; no se fabrican respuestas originales ausentes.
-- Si existen cierres duplicados, la migración falla para exigir su revisión.
CREATE UNIQUE INDEX uq_historial_cierre_despacho
    ON public.historial_estados_despacho (despacho_id)
    WHERE tipo_operacion = 'TRANSICION' AND estado_nuevo = 'DEVUELTO_A_ORIGEN';
