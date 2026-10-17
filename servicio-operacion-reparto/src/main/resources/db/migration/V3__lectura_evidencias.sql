-- Metadatos compartidos con F-03; la consulta no crea ni asocia fotografías.
CREATE TABLE evidencias (
    id uuid PRIMARY KEY,
    despacho_id_externo uuid NOT NULL,
    ruta_objeto varchar(300) NOT NULL UNIQUE,
    tipo varchar(10) NOT NULL CHECK (tipo IN ('ENTREGA', 'FALLO')),
    tipo_mime varchar(30) NOT NULL CHECK (tipo_mime IN ('image/jpeg', 'image/png', 'image/webp')),
    tamano_bytes integer NOT NULL CHECK (tamano_bytes > 0 AND tamano_bytes <= 2097152),
    estado varchar(12) NOT NULL CHECK (estado IN ('AUTORIZADA', 'CARGADA', 'ASOCIADA', 'EXPIRADA')),
    cargado_por varchar(64) NOT NULL,
    autorizada_en timestamptz NOT NULL,
    cargado_en timestamptz,
    asociada_en timestamptz,
    expira_en timestamptz NOT NULL
);
CREATE INDEX idx_evidencias_despacho ON evidencias(despacho_id_externo);
CREATE TABLE parametros_configuracion (
    clave varchar(50) PRIMARY KEY,
    valor varchar(100) NOT NULL,
    descripcion varchar(200) NOT NULL,
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_por text NOT NULL
);
INSERT INTO parametros_configuracion(clave, valor, descripcion, actualizado_por)
VALUES ('VIGENCIA_URL_FIRMADA_SEGUNDOS', '300', 'Vigencia de lectura temporal de evidencia privada', 'SISTEMA');
