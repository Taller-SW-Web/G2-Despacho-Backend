ALTER TABLE proyecciones_despacho
    ADD COLUMN maximo_intentos integer CHECK (maximo_intentos IS NULL OR maximo_intentos > 0);
