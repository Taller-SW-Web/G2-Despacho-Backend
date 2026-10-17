-- F-01: Zonas geograficas, catalogo de distritos, matriz tarifaria y trazabilidad.
-- La delimitacion se expresa por distrito y/o codigo postal. Las columnas geometricas
-- (geom / PostGIS) descritas en el modelo de datos se incorporaran en una entrega posterior.

CREATE OR REPLACE FUNCTION public.set_actualizado_en()
RETURNS trigger AS $$
BEGIN
    NEW.actualizado_en = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE distrito (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre          text        NOT NULL,
    provincia       text,
    departamento    text,
    ubigeo          text,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    actualizado_en  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_distrito_ubigeo UNIQUE (ubigeo)
);
CREATE INDEX idx_distrito_nombre ON distrito (lower(nombre));

CREATE TABLE zonas (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre                text        NOT NULL,
    estado                text        NOT NULL DEFAULT 'ACTIVO'
        CONSTRAINT ck_zonas_estado CHECK (estado IN ('ACTIVO', 'INACTIVO')),
    origen_delimitacion   text,
    creado_en             timestamptz NOT NULL DEFAULT now(),
    actualizado_en        timestamptz NOT NULL DEFAULT now(),
    creado_por            uuid,
    actualizado_por       uuid
);
CREATE UNIQUE INDEX uq_zonas_nombre ON zonas (lower(nombre));

CREATE TABLE zonas_distritos (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    zona_id         uuid        NOT NULL REFERENCES zonas (id) ON DELETE CASCADE,
    distrito_id     uuid        REFERENCES distrito (id),
    codigo_postal   text,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    actualizado_en  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_zonas_distritos_cobertura CHECK (distrito_id IS NOT NULL OR codigo_postal IS NOT NULL)
);
CREATE UNIQUE INDEX uq_zonas_distritos_distrito
    ON zonas_distritos (zona_id, distrito_id) WHERE distrito_id IS NOT NULL;
CREATE UNIQUE INDEX uq_zonas_distritos_codigo_postal
    ON zonas_distritos (zona_id, codigo_postal) WHERE codigo_postal IS NOT NULL;
CREATE INDEX idx_zonas_distritos_distrito ON zonas_distritos (distrito_id);
CREATE INDEX idx_zonas_distritos_codigo_postal ON zonas_distritos (codigo_postal);

CREATE TABLE tarifas_zona (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    zona_id                uuid          NOT NULL REFERENCES zonas (id),
    tarifa_base            numeric(12,2) NOT NULL CHECK (tarifa_base >= 0),
    peso_base_kg           numeric(10,2) CHECK (peso_base_kg > 0),
    recargo_kg_adicional   numeric(12,2) CHECK (recargo_kg_adicional >= 0),
    factor_volumetrico     numeric(10,2) CHECK (factor_volumetrico > 0),
    moneda                 text,
    plazo_min_dias         integer,
    plazo_max_dias         integer,
    activa                 boolean       NOT NULL DEFAULT true,
    creado_en              timestamptz   NOT NULL DEFAULT now(),
    creado_por             uuid,
    actualizado_en         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_tarifas_zona_plazo CHECK (plazo_min_dias IS NULL OR plazo_max_dias IS NULL
        OR plazo_min_dias <= plazo_max_dias)
);
CREATE UNIQUE INDEX uq_tarifas_zona_activa ON tarifas_zona (zona_id) WHERE activa;
CREATE INDEX idx_tarifas_zona_zona ON tarifas_zona (zona_id);

CREATE TABLE zona_conflictos_log (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_intentado    text,
    zona_conflicto_id   uuid        REFERENCES zonas (id),
    motivo              text,
    detalle             text,
    intentado_en        timestamptz NOT NULL DEFAULT now(),
    intentado_por       uuid,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_zona_conflictos_log_zona ON zona_conflictos_log (zona_conflicto_id);

CREATE TABLE auditoria_configuracion (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    entidad           varchar(30) NOT NULL,
    entidad_id        varchar(64) NOT NULL,
    accion            varchar(20) NOT NULL
        CONSTRAINT ck_auditoria_configuracion_accion CHECK (accion IN ('CREAR', 'MODIFICAR', 'CAMBIAR_ESTADO')),
    datos_anteriores  jsonb,
    datos_nuevos      jsonb,
    usuario_id        varchar(64) NOT NULL,
    registrado_en     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_auditoria_configuracion_entidad ON auditoria_configuracion (entidad, entidad_id);

CREATE TABLE parametros_configuracion (
    clave           varchar(50)  PRIMARY KEY,
    valor           varchar(100) NOT NULL,
    descripcion     varchar(200) NOT NULL,
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    actualizado_por varchar(64)  NOT NULL
);

INSERT INTO parametros_configuracion (clave, valor, descripcion, actualizado_por) VALUES
    ('LIMITE_COTIZACIONES_POR_MINUTO', '60', 'Limite de solicitudes de cotizacion por sub e IP', 'sistema'),
    ('MAXIMO_INTENTOS', '2', 'Maximo de intentos de entrega por despacho', 'sistema')
ON CONFLICT (clave) DO NOTHING;

CREATE TRIGGER trg_distrito_actualizado_en BEFORE UPDATE ON distrito
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_zonas_actualizado_en BEFORE UPDATE ON zonas
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_zonas_distritos_actualizado_en BEFORE UPDATE ON zonas_distritos
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_tarifas_zona_actualizado_en BEFORE UPDATE ON tarifas_zona
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_zona_conflictos_log_actualizado_en BEFORE UPDATE ON zona_conflictos_log
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
