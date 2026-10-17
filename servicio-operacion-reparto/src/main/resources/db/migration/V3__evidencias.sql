CREATE TABLE evidencias (
    id uuid PRIMARY KEY,
    despacho_id_externo uuid NOT NULL,
    ruta_objeto varchar(300) NOT NULL UNIQUE,
    tipo varchar(10) NOT NULL CHECK (tipo IN ('ENTREGA', 'FALLO')),
    tipo_mime varchar(30) NOT NULL,
    tamano_bytes integer NOT NULL CHECK (tamano_bytes > 0),
    estado varchar(12) NOT NULL CHECK (estado IN ('AUTORIZADA', 'CARGADA', 'ASOCIADA', 'EXPIRADA')),
    cargado_por varchar(64) NOT NULL,
    autorizada_en timestamptz NOT NULL,
    cargado_en timestamptz,
    asociada_en timestamptz,
    expira_en timestamptz NOT NULL
);

CREATE INDEX ix_evidencias_despacho_estado
    ON evidencias (despacho_id_externo, estado);
