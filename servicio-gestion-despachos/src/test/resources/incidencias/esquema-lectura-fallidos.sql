-- Esquema mínimo exclusivo de las pruebas del lector de T-06.
-- No es una migración de producción ni sustituye las migraciones de F-02/F-03.
CREATE TABLE parametros_configuracion (
    clave varchar(50) PRIMARY KEY,
    valor varchar(100) NOT NULL
);

CREATE TABLE motivos_fallo (
    codigo text PRIMARY KEY,
    etiqueta text NOT NULL,
    seleccionable_en_campo boolean NOT NULL,
    consume_intento boolean NOT NULL,
    activo boolean NOT NULL
);

CREATE TABLE despachos (
    id uuid PRIMARY KEY,
    estado text NOT NULL,
    version integer NOT NULL,
    codigo_rastreo_interno text,
    numero_intentos integer NOT NULL,
    ultima_incidencia_id uuid,
    pedido_anulado_en timestamptz,
    repartidor_id_externo uuid,
    asignacion_diaria_id_externa uuid
);

CREATE TABLE intentos_entrega (
    id uuid PRIMARY KEY,
    despacho_id uuid NOT NULL REFERENCES despachos(id),
    resultado text NOT NULL,
    motivo_codigo text REFERENCES motivos_fallo(codigo),
    consume_intento boolean NOT NULL,
    repartidor_id_externo uuid,
    asignacion_diaria_id_externa uuid,
    registrado_en timestamptz NOT NULL,
    recibido_en_centro_en timestamptz,
    evidencia_id_externa uuid
);
