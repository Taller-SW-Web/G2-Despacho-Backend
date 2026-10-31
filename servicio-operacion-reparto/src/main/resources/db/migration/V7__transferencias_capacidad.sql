-- Una operación durable, independiente de las claves de asignación inicial.
CREATE TABLE transferencias_capacidad (
    clave uuid PRIMARY KEY,
    solicitud text NOT NULL,
    respuesta jsonb NOT NULL,
    ejecutor_id text NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT now()
);
