-- Las medidas exactas del proveedor no se limitan a centésimos. El volumen presentado
-- mantiene NUMERIC(10,3); detalle conserva volumen exacto, desglose y ajuste monetario.
ALTER TABLE cotizaciones ALTER COLUMN peso_kg TYPE numeric;
ALTER TABLE cotizaciones ALTER COLUMN peso_volumetrico_kg TYPE numeric;
ALTER TABLE cotizaciones ALTER COLUMN peso_cobrable_kg TYPE numeric;
