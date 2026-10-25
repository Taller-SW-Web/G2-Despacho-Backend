-- ES-F04-04: resultado durable de cada decisión; no modifica las tablas de Operación.
-- Se aplica sobre V1/V2; no reconstruye respuestas históricas inexistentes.
CREATE TABLE public.reprogramaciones_despacho (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES public.despachos (id),
    incidencia_id uuid NOT NULL,
    version_solicitada integer NOT NULL CHECK (version_solicitada > 0),
    ejecutor_id uuid NOT NULL,
    hash_contenido text NOT NULL,
    fecha_programada date NOT NULL CHECK (fecha_programada BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    observacion varchar(500),
    historial_id uuid NOT NULL UNIQUE REFERENCES public.historial_estados_despacho (id),
    evento_id uuid UNIQUE REFERENCES public.eventos_salientes (id),
    codigo_respuesta integer NOT NULL CHECK (codigo_respuesta = 200),
    respuesta_original jsonb NOT NULL,
    creado_en timestamptz NOT NULL,
    UNIQUE (despacho_id, incidencia_id, version_solicitada),
    FOREIGN KEY (incidencia_id, despacho_id) REFERENCES public.intentos_entrega (id, despacho_id)
);
