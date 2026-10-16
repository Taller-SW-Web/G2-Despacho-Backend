-- Solo datos sintéticos de la demo aislada; no es una migración de producción.
DO $$
DECLARE
    n integer;
    despacho uuid;
    incidencia uuid;
BEGIN
    IF current_database() <> 'f04_gestion_demo' THEN
        RAISE EXCEPTION 'Los datos de demo solo se aplican a f04_gestion_demo';
    END IF;
    FOR n IN 1..13 LOOP
        despacho := ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid;
        INSERT INTO despachos (id, pedido_id, codigo_rastreo_interno, es_simulado, estado, numero_intentos, version)
        VALUES (despacho, ('00000000-0000-0000-0000-' || lpad((500+n)::text, 12, '0'))::uuid,
            'DEMO-F04-' || lpad(n::text, 2, '0'), true, 'PENDIENTE_ASIGNACION', 1, 4)
        ON CONFLICT (id) DO UPDATE SET estado = 'PENDIENTE_ASIGNACION', numero_intentos = 1, version = 4;
        IF n <= 12 THEN
            incidencia := ('00000000-0000-0000-0000-' || lpad((100+n)::text, 12, '0'))::uuid;
            INSERT INTO intentos_entrega (id, despacho_id, resultado, motivo_codigo, consume_intento,
                repartidor_id_externo, asignacion_diaria_id_externa, registrado_en, recibido_en_centro_en, origen)
            VALUES (incidencia, despacho, 'FALLIDO', 'NO_INTENTADO', false,
                ('00000000-0000-0000-0000-' || lpad((200+n)::text, 12, '0'))::uuid,
                ('00000000-0000-0000-0000-' || lpad((300+n)::text, 12, '0'))::uuid,
                CASE WHEN n <= 5 THEN '2026-10-15T18:00:00Z'::timestamptz ELSE '2026-10-14T18:00:00Z'::timestamptz END,
                CASE WHEN n <= 5 THEN '2026-10-15T20:00:00Z'::timestamptz ELSE NULL END, 'DEMO_SINTETICA')
            ON CONFLICT (id) DO NOTHING;
            UPDATE despachos SET estado = 'FALLIDO', ultima_incidencia_id = incidencia WHERE id = despacho;
        END IF;
    END LOOP;
END;
$$;
