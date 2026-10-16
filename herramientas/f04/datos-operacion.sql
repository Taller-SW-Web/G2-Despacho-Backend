-- Perfiles/jornadas sintéticos en la base exclusiva de la demo de Operación.
DO $$
DECLARE
    n integer;
    persona uuid;
    vehiculo uuid;
    jornada uuid;
    actor uuid := '11111111-1111-4111-8111-111111111111';
BEGIN
    IF current_database() <> 'f04_operacion_demo' THEN
        RAISE EXCEPTION 'Los datos de demo solo se aplican a f04_operacion_demo';
    END IF;
    FOR n IN 1..12 LOOP
        persona := ('00000000-0000-0000-0000-' || lpad((200+n)::text, 12, '0'))::uuid;
        vehiculo := ('00000000-0000-0000-0000-' || lpad((400+n)::text, 12, '0'))::uuid;
        jornada := ('00000000-0000-0000-0000-' || lpad((300+n)::text, 12, '0'))::uuid;
        INSERT INTO repartidores (id, nombres, apellidos, dni, telefono, correo, numero_brevete,
            turno_habitual, estado_registro, estado_vinculacion, creado_por, actualizado_por)
        VALUES (persona, 'Persona de prueba', n::text, lpad(n::text, 8, '0'), '000000000',
            'demo-f04-' || n || '@example.invalid', 'DEMO-' || n, 'COMPLETO', 'ACTIVO', 'PENDIENTE', actor, actor)
        ON CONFLICT (id) DO NOTHING;
        INSERT INTO furgonetas (id, placa, estado, capacidad_max_kg, capacidad_max_m3, max_paquetes, creado_por, actualizado_por)
        VALUES (vehiculo, 'DEMO-' || lpad(n::text, 3, '0'), 'DISPONIBLE', 100, 2, 20, actor, actor)
        ON CONFLICT (id) DO NOTHING;
        INSERT INTO asignaciones_diarias (id, repartidor_id, furgoneta_id, zona_id_externa, fecha_jornada,
            capacidad_max_kg, capacidad_max_m3, max_paquetes, estado, abierta_por, cerrada_en, tipo_cierre)
        VALUES (jornada, persona, vehiculo, '00000000-0000-0000-0000-000000000999', '2026-10-14',
            100, 2, 20, CASE WHEN n IN (6,7) THEN 'CERRADA' ELSE 'ACTIVA' END, actor,
            CASE WHEN n IN (6,7) THEN '2026-10-15T23:00:00Z'::timestamptz ELSE NULL END,
            CASE WHEN n IN (6,7) THEN 'REPARTIDOR' ELSE NULL END)
        ON CONFLICT (id) DO NOTHING;
    END LOOP;
END;
$$;
