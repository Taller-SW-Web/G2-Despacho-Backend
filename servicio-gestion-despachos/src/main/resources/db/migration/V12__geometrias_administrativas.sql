-- Metadatos geográficos independientes de la identidad y versión del CRUD de distritos.
CREATE TABLE public.validaciones_geograficas (
    id UUID PRIMARY KEY,
    ejecutor_id TEXT NOT NULL,
    expira_en TIMESTAMPTZ NOT NULL,
    propuesta JSONB NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.importaciones_geograficas (
    id UUID PRIMARY KEY,
    validacion_id UUID NOT NULL UNIQUE REFERENCES public.validaciones_geograficas(id),
    fuente TEXT NOT NULL,
    version_fuente TEXT NOT NULL,
    nombre_archivo TEXT CHECK (nombre_archivo IS NULL OR length(nombre_archivo) BETWEEN 1 AND 255),
    ejecutor_id TEXT NOT NULL,
    importado_en TIMESTAMPTZ NOT NULL
);

CREATE TABLE public.geometrias_distrito (
    distrito_id UUID PRIMARY KEY REFERENCES public.distrito(id),
    version BIGINT NOT NULL CHECK (version > 0),
    fuente TEXT NOT NULL,
    version_fuente TEXT NOT NULL,
    importado_por TEXT NOT NULL,
    importado_en TIMESTAMPTZ NOT NULL,
    huella TEXT NOT NULL CHECK (length(huella) = 64),
    importacion_id UUID NOT NULL REFERENCES public.importaciones_geograficas(id)
);

CREATE TABLE public.historico_geometrias (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    distrito_id UUID NOT NULL REFERENCES public.distrito(id),
    version BIGINT NOT NULL CHECK (version >= 0),
    geom extensions.geometry(Geometry, 4326) NOT NULL,
    metadatos JSONB,
    sustituido_en TIMESTAMPTZ NOT NULL,
    sustituido_por TEXT NOT NULL,
    importacion_id UUID NOT NULL REFERENCES public.importaciones_geograficas(id),
    UNIQUE (distrito_id, version)
);

CREATE INDEX idx_validaciones_geograficas_ejecutor ON public.validaciones_geograficas(ejecutor_id, expira_en);
CREATE INDEX idx_historico_geometrias_distrito ON public.historico_geometrias(distrito_id, sustituido_en);
