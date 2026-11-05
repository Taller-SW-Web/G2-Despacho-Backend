CREATE TABLE comandos_configuracion (
    ejecutor_id text NOT NULL,
    clave uuid NOT NULL,
    operacion text NOT NULL,
    huella text NOT NULL,
    respuesta jsonb NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (ejecutor_id, clave)
);
