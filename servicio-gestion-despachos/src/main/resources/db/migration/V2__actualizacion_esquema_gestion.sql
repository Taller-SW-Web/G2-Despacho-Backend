-- V2: cambios desde V1 hasta la estructura de Gestión consultada en Supabase.
-- Historia unificada para validación local, 22/10/2026 (America/Lima).
-- Precondición: V1 aplicada; no es un script para volver a ejecutar en Supabase actual.
-- Flyway administra la transacción: no incluir ROLLBACK, BEGIN ni COMMIT manuales.
-- Mantiene las restricciones observadas; no agrega las validaciones más estrictas
-- de las migraciones anteriores del repositorio que no están desplegadas.
-- Los UUID externos existentes se conservan como texto; las PK propias siguen UUID.

-- Nombre del catálogo usado por el backend actual. Conserva sus datos y referencias.
ALTER TABLE public.motivo_fallo RENAME TO motivos_fallo;

-- Configuración y auditoría.
CREATE TABLE public.parametros_configuracion (
    clave varchar(50) PRIMARY KEY,
    valor varchar(100) NOT NULL,
    descripcion varchar(200) NOT NULL,
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_por varchar(64) NOT NULL
);

INSERT INTO public.parametros_configuracion
    (clave, valor, descripcion, actualizado_por)
VALUES
    ('MAXIMO_INTENTOS', '2',
     'Maximo de intentos de entrega por despacho', 'sistema'),
    ('LIMITE_COTIZACIONES_POR_MINUTO', '60',
     'Limite de solicitudes de cotizacion por sub e IP', 'sistema')
ON CONFLICT (clave) DO NOTHING;

CREATE TABLE public.auditoria_configuracion (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entidad varchar(30) NOT NULL,
    entidad_id varchar(64) NOT NULL,
    accion varchar(20) NOT NULL
        CONSTRAINT ck_auditoria_configuracion_accion CHECK (accion IN ('CREAR', 'MODIFICAR', 'CAMBIAR_ESTADO')),
    datos_anteriores jsonb,
    datos_nuevos jsonb,
    usuario_id varchar(64) NOT NULL,
    registrado_en timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_auditoria_configuracion_entidad
    ON public.auditoria_configuracion (entidad, entidad_id);


-- Columnas de despachos e intentos.
ALTER TABLE public.despachos
    ADD COLUMN codigo_operativo text,
    ADD COLUMN departamento text,
    ADD COLUMN provincia text,
    ADD COLUMN observacion_asignacion text,
    ADD COLUMN ultima_incidencia_id uuid;

ALTER TABLE public.intentos_entrega
    ADD COLUMN reserva_capacidad_id_externa text;

ALTER TABLE public.despachos
    ALTER COLUMN pedido_id
        TYPE text USING pedido_id::text,
    ALTER COLUMN repartidor_id_externo
        TYPE text USING repartidor_id_externo::text,
    ALTER COLUMN asignacion_diaria_id_externa
        TYPE text USING asignacion_diaria_id_externa::text,
    ALTER COLUMN reserva_capacidad_id_externa
        TYPE text USING reserva_capacidad_id_externa::text,
    ALTER COLUMN peso_kg TYPE numeric,
    ALTER COLUMN volumen_m3 TYPE numeric;

ALTER TABLE public.intentos_entrega
    ALTER COLUMN repartidor_id_externo
        TYPE text USING repartidor_id_externo::text,
    ALTER COLUMN asignacion_diaria_id_externa
        TYPE text USING asignacion_diaria_id_externa::text,
    ALTER COLUMN reserva_capacidad_id_externa
        TYPE text USING reserva_capacidad_id_externa::text;

UPDATE public.despachos
SET codigo_operativo = 'DSP-' || id::text
WHERE codigo_operativo IS NULL;

CREATE UNIQUE INDEX uq_despachos_codigo_operativo
    ON public.despachos (codigo_operativo);

CREATE UNIQUE INDEX uq_despachos_pedido
    ON public.despachos (pedido_id);

CREATE UNIQUE INDEX uq_despachos_posicion_jornada
    ON public.despachos (
        asignacion_diaria_id_externa,
        secuencia_ruta
    )
    WHERE asignacion_diaria_id_externa IS NOT NULL
      AND secuencia_ruta IS NOT NULL;

CREATE INDEX idx_despachos_pendientes
    ON public.despachos (fecha_programada, creado_en, id)
    WHERE estado = 'PENDIENTE_ASIGNACION';

CREATE UNIQUE INDEX uq_intentos_entrega_propietario
    ON public.intentos_entrega (id, despacho_id);

ALTER TABLE public.despachos
    ADD CONSTRAINT fk_despachos_ultima_incidencia
    FOREIGN KEY (ultima_incidencia_id, id)
    REFERENCES public.intentos_entrega (id, despacho_id)
    DEFERRABLE INITIALLY DEFERRED;


-- Numeración de despachos: conservar el avance existente.
CREATE SEQUENCE public.secuencia_codigos_despacho;

WITH usados AS (
    SELECT max(substring(codigo FROM '([0-9]+)$')::bigint) AS maximo
    FROM (
        SELECT codigo_operativo AS codigo
        FROM public.despachos

        UNION ALL

        SELECT codigo_rastreo_interno
        FROM public.despachos
    ) codigos
    WHERE codigo ~ '^(DSP|TRK)-(SIM-)?[0-9]+$'
)
SELECT setval(
    'public.secuencia_codigos_despacho',
    usados.maximo,
    true
)
FROM usados, public.secuencia_codigos_despacho secuencia
WHERE usados.maximo > secuencia.last_value
   OR (
       usados.maximo = secuencia.last_value
       AND NOT secuencia.is_called
   );


-- Operaciones idempotentes.
CREATE TABLE public.claves_idempotencia (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    clave text NOT NULL,
    actor_tipo text NOT NULL,
    actor_id text NOT NULL,
    operacion text NOT NULL,
    huella_solicitud text NOT NULL,
    estado text NOT NULL
        CHECK (estado IN ('EN_PROCESO', 'COMPLETADA', 'FALLIDA')),
    codigo_respuesta integer,
    respuesta jsonb,
    creado_en timestamptz NOT NULL DEFAULT now(),
    expira_en timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    UNIQUE (actor_tipo, actor_id, operacion, clave)
);

CREATE INDEX idx_claves_idempotencia_expira
    ON public.claves_idempotencia (expira_en);


-- Asignaciones de despachos.
CREATE TABLE public.asignaciones_despacho (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES public.despachos (id),
    version_origen integer NOT NULL,
    ejecutor_id text NOT NULL,
    id_repartidor text NOT NULL,
    id_jornada text NOT NULL,
    observacion text,
    peso_requerido numeric NOT NULL CHECK (peso_requerido > 0),
    volumen_requerido numeric NOT NULL CHECK (volumen_requerido > 0),
    paquetes_requeridos integer NOT NULL CHECK (paquetes_requeridos > 0),
    estado text NOT NULL CHECK (estado IN (
        'PENDIENTE', 'CONFIRMADA', 'RECHAZADA',
        'COMPENSACION_PENDIENTE', 'COMPENSADA'
    )),
    id_reserva text,
    respuesta jsonb,
    procesando_hasta timestamptz,
    proximo_intento_en timestamptz NOT NULL DEFAULT now(),
    creado_en timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_asignacion_version_activa
    ON public.asignaciones_despacho (despacho_id, version_origen)
    WHERE estado NOT IN ('RECHAZADA', 'COMPENSADA');

CREATE INDEX idx_asignaciones_recuperables
    ON public.asignaciones_despacho (estado, proximo_intento_en);


-- Historial y eventos.
ALTER TABLE public.historial_estados_despacho
    ALTER COLUMN datos SET DEFAULT '{}'::jsonb;

CREATE INDEX idx_historial_despacho
    ON public.historial_estados_despacho (
        despacho_id,
        registrado_en,
        id
    );

ALTER TABLE public.eventos_salientes
    ADD COLUMN bloqueada_hasta timestamptz,
    ADD COLUMN bloqueo_id uuid,
    ALTER COLUMN estado_envio SET DEFAULT 'PENDIENTE',
    ALTER COLUMN proximo_intento_en SET DEFAULT now();

UPDATE public.eventos_salientes
SET proximo_intento_en = creado_en
WHERE estado_envio = 'PENDIENTE'
  AND proximo_intento_en IS NULL;


-- Completar motivos sin reemplazar los existentes.
INSERT INTO public.motivos_fallo
    (codigo, etiqueta, seleccionable_en_campo, consume_intento)
VALUES
    ('CLIENTE_AUSENTE', 'Cliente ausente', true, true),
    ('DIRECCION_NO_UBICADA', 'Dirección no localizada', true, true),
    ('RECHAZO_DEL_PAQUETE', 'Cliente rechaza el paquete', true, true),
    ('DATOS_DE_CONTACTO_ERRONEOS', 'Datos de contacto incorrectos', true, true),
    ('ZONA_INACCESIBLE', 'Zona inaccesible o insegura', true, true),
    ('PAQUETE_DANADO', 'Paquete dañado antes de la entrega', true, true),
    ('NO_INTENTADO', 'No intentado por fin de jornada', false, false)
ON CONFLICT (codigo) DO NOTHING;


-- Liberaciones consultadas por el detalle de incidencia.
CREATE TABLE public.liberaciones_capacidad (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES public.despachos (id),
    incidencia_id uuid NOT NULL UNIQUE,
    reserva_id_externa text NOT NULL UNIQUE,
    jornada_id_externa text NOT NULL,
    solicitada_por text NOT NULL,
    version_solicitada integer NOT NULL CHECK (version_solicitada > 0),
    hash_contenido text NOT NULL,
    respuesta_original jsonb NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PENDIENTE', 'CONFIRMADA')),
    requiere_revision boolean NOT NULL DEFAULT false,
    intentos_envio integer NOT NULL DEFAULT 0 CHECK (intentos_envio >= 0),
    proximo_intento_en timestamptz,
    ultimo_intento_en timestamptz,
    confirmada_en timestamptz,
    ultimo_codigo_error text,
    bloqueada_hasta timestamptz,
    token_procesamiento uuid,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),

    FOREIGN KEY (incidencia_id, despacho_id)
        REFERENCES public.intentos_entrega (id, despacho_id),

    CHECK (
        (
            estado = 'PENDIENTE'
            AND confirmada_en IS NULL
            AND proximo_intento_en IS NOT NULL
        )
        OR
        (
            estado = 'CONFIRMADA'
            AND confirmada_en IS NOT NULL
            AND NOT requiere_revision
            AND proximo_intento_en IS NULL
        )
    )
);

CREATE INDEX idx_liberaciones_pendientes
    ON public.liberaciones_capacidad (
        estado,
        proximo_intento_en,
        bloqueada_hasta
    );

CREATE TRIGGER trg_liberaciones_actualizado_en
    BEFORE UPDATE ON public.liberaciones_capacidad
    FOR EACH ROW
    EXECUTE FUNCTION public.set_actualizado_en();


-- Integridad de zonas y tarifas.
CREATE UNIQUE INDEX uq_zonas_nombre
    ON public.zonas (lower(nombre));

CREATE UNIQUE INDEX uq_tarifas_zona_activa
    ON public.tarifas_zona (zona_id)
    WHERE activa;

CREATE INDEX idx_distrito_nombre
    ON public.distrito (lower(nombre));


-- Cotizaciones: script adicional compartido por el equipo.
-- pedido_id permanece UUID, igual que en la base consultada. No admite códigos PED-...
-- hasta que una migración futura concilie su contrato con despachos.pedido_id (text).
CREATE TABLE public.cotizaciones (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    zona_id uuid REFERENCES public.zonas (id),
    tarifa_zona_id uuid REFERENCES public.tarifas_zona (id),
    distrito_id uuid REFERENCES public.distrito (id),
    direccion text,
    distrito text,
    codigo_postal text,
    latitud numeric(9,6),
    longitud numeric(9,6),
    peso_kg numeric(10,2),
    volumen_m3 numeric(10,3),
    cantidad_paquetes integer,
    tarifa_base numeric(12,2) NOT NULL,
    peso_base_kg numeric(10,2),
    recargo_kg_adicional numeric(12,2),
    factor_volumetrico numeric(10,2),
    peso_volumetrico_kg numeric(10,2),
    peso_cobrable_kg numeric(10,2),
    recargo_total numeric(12,2) NOT NULL DEFAULT 0,
    total numeric(12,2) NOT NULL,
    moneda text,
    plazo_min_dias integer,
    plazo_max_dias integer,
    estado text NOT NULL DEFAULT 'vigente',
    vence_en timestamptz,
    es_simulado boolean NOT NULL DEFAULT false,
    pedido_id uuid,
    despacho_id uuid REFERENCES public.despachos (id),
    detalle jsonb,
    creado_en timestamptz NOT NULL DEFAULT now(),
    creado_por uuid,
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_por uuid
);

CREATE INDEX idx_cotizaciones_zona ON public.cotizaciones (zona_id);
CREATE INDEX idx_cotizaciones_tarifa ON public.cotizaciones (tarifa_zona_id);
CREATE INDEX idx_cotizaciones_despacho ON public.cotizaciones (despacho_id);
CREATE INDEX idx_cotizaciones_pedido ON public.cotizaciones (pedido_id);
CREATE INDEX idx_cotizaciones_estado ON public.cotizaciones (estado, vence_en);

CREATE TRIGGER trg_cotizaciones_actualizado_en
    BEFORE UPDATE ON public.cotizaciones
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();


-- Códigos postales por zona y distrito: script adicional compartido por el equipo.
CREATE TABLE public.zonas_codigos_postales (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    zona_id uuid NOT NULL REFERENCES public.zonas (id) ON DELETE CASCADE,
    distrito_id uuid NOT NULL REFERENCES public.distrito (id),
    codigo_postal text NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_zcp UNIQUE (zona_id, distrito_id, codigo_postal)
);

CREATE INDEX idx_zcp_zona ON public.zonas_codigos_postales (zona_id);
CREATE INDEX idx_zcp_cp ON public.zonas_codigos_postales (codigo_postal);
CREATE TRIGGER trg_zcp_actualizado_en
    BEFORE UPDATE ON public.zonas_codigos_postales
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();


-- Catálogo de referencia: 43 distritos de Lima y 7 del Callao.
-- Se reproduce el catálogo versionado en el repositorio, no filas de negocio de Supabase.
-- 43 distritos de Lima provincia y 7 del Callao (codigos INEI).
-- Referencia: catalogo de equivalencias de CONCYTEC, columna cod_ubigeo_inei:
-- https://github.com/CONCYTEC/ubigeo-peru/blob/dd810cb30d0c6ff940bfe5a17d89e4ff7abb201d/equivalencia-ubigeos-oti-concytec.csv
-- Conserva los UUID, nombres y demas datos de los distritos existentes.
INSERT INTO public.distrito (nombre, provincia, departamento, ubigeo) VALUES
    ('Lima', 'Lima', 'Lima', '150101'),
    ('Ancón', 'Lima', 'Lima', '150102'),
    ('Ate', 'Lima', 'Lima', '150103'),
    ('Barranco', 'Lima', 'Lima', '150104'),
    ('Breña', 'Lima', 'Lima', '150105'),
    ('Carabayllo', 'Lima', 'Lima', '150106'),
    ('Chaclacayo', 'Lima', 'Lima', '150107'),
    ('Chorrillos', 'Lima', 'Lima', '150108'),
    ('Cieneguilla', 'Lima', 'Lima', '150109'),
    ('Comas', 'Lima', 'Lima', '150110'),
    ('El Agustino', 'Lima', 'Lima', '150111'),
    ('Independencia', 'Lima', 'Lima', '150112'),
    ('Jesús María', 'Lima', 'Lima', '150113'),
    ('La Molina', 'Lima', 'Lima', '150114'),
    ('La Victoria', 'Lima', 'Lima', '150115'),
    ('Lince', 'Lima', 'Lima', '150116'),
    ('Los Olivos', 'Lima', 'Lima', '150117'),
    ('Lurigancho', 'Lima', 'Lima', '150118'),
    ('Lurín', 'Lima', 'Lima', '150119'),
    ('Magdalena del Mar', 'Lima', 'Lima', '150120'),
    ('Pueblo Libre', 'Lima', 'Lima', '150121'),
    ('Miraflores', 'Lima', 'Lima', '150122'),
    ('Pachacámac', 'Lima', 'Lima', '150123'),
    ('Pucusana', 'Lima', 'Lima', '150124'),
    ('Puente Piedra', 'Lima', 'Lima', '150125'),
    ('Punta Hermosa', 'Lima', 'Lima', '150126'),
    ('Punta Negra', 'Lima', 'Lima', '150127'),
    ('Rímac', 'Lima', 'Lima', '150128'),
    ('San Bartolo', 'Lima', 'Lima', '150129'),
    ('San Borja', 'Lima', 'Lima', '150130'),
    ('San Isidro', 'Lima', 'Lima', '150131'),
    ('San Juan de Lurigancho', 'Lima', 'Lima', '150132'),
    ('San Juan de Miraflores', 'Lima', 'Lima', '150133'),
    ('San Luis', 'Lima', 'Lima', '150134'),
    ('San Martín de Porres', 'Lima', 'Lima', '150135'),
    ('San Miguel', 'Lima', 'Lima', '150136'),
    ('Santa Anita', 'Lima', 'Lima', '150137'),
    ('Santa María del Mar', 'Lima', 'Lima', '150138'),
    ('Santa Rosa', 'Lima', 'Lima', '150139'),
    ('Santiago de Surco', 'Lima', 'Lima', '150140'),
    ('Surquillo', 'Lima', 'Lima', '150141'),
    ('Villa El Salvador', 'Lima', 'Lima', '150142'),
    ('Villa María del Triunfo', 'Lima', 'Lima', '150143'),
    ('Callao', 'Callao', 'Callao', '070101'),
    ('Bellavista', 'Callao', 'Callao', '070102'),
    ('Carmen de la Legua Reynoso', 'Callao', 'Callao', '070103'),
    ('La Perla', 'Callao', 'Callao', '070104'),
    ('La Punta', 'Callao', 'Callao', '070105'),
    ('Ventanilla', 'Callao', 'Callao', '070106'),
    ('Mi Perú', 'Callao', 'Callao', '070107')
ON CONFLICT (ubigeo) DO NOTHING;
