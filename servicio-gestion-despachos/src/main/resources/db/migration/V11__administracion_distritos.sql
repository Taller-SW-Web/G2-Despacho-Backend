-- Conserva identidades, asociaciones e históricos del catálogo existente.
ALTER TABLE public.distrito
    ADD COLUMN estado TEXT NOT NULL DEFAULT 'ACTIVO',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN catalogo_base BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_distrito_estado CHECK (estado IN ('ACTIVO', 'INACTIVO')),
    ADD CONSTRAINT ck_distrito_version CHECK (version >= 0),
    ADD CONSTRAINT ck_distrito_nombre CHECK (length(btrim(nombre)) BETWEEN 1 AND 100);

-- V2 precargó los 43 ubigeos correlativos de Lima y los siete del Callao.
-- Marcar su procedencia no es una edición administrativa; conserva también las fechas previas.
ALTER TABLE public.distrito DISABLE TRIGGER trg_distrito_actualizado_en;
UPDATE public.distrito SET catalogo_base = true
WHERE ubigeo IN (SELECT '1501' || lpad(codigo::text, 2, '0') FROM generate_series(1, 43) codigo)
   OR ubigeo IN (SELECT '0701' || lpad(codigo::text, 2, '0') FROM generate_series(1, 7) codigo);
ALTER TABLE public.distrito ENABLE TRIGGER trg_distrito_actualizado_en;

CREATE UNIQUE INDEX uq_distrito_nombre_normalizado ON public.distrito (lower(btrim(nombre)));
CREATE INDEX idx_distrito_estado_nombre ON public.distrito (estado, nombre);
