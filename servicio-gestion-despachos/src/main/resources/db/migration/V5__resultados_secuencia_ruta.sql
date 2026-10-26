-- Una permutación puede intercambiar dos posiciones sin colisionar a mitad del lote.
DROP INDEX uq_despachos_posicion_jornada;
ALTER TABLE despachos ADD CONSTRAINT uq_despachos_posicion_jornada
    UNIQUE (asignacion_diaria_id_externa, secuencia_ruta) DEFERRABLE INITIALLY DEFERRED;

-- Resultado durable: repetir un guardado recupera su respuesta incluso si la ruta avanzó.
CREATE TABLE resultados_secuencia_ruta (
    ejecutor_id text NOT NULL,
    clave uuid NOT NULL,
    solicitud text NOT NULL,
    respuesta jsonb NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (ejecutor_id, clave)
);
