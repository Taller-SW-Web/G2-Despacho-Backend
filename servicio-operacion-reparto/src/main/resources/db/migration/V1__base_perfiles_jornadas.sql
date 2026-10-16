-- Tablas canónicas de Operación, modelo de datos §3.2.
CREATE OR REPLACE FUNCTION public.set_actualizado_en()
RETURNS trigger AS $$
BEGIN
    NEW.actualizado_en = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE repartidores (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nombres varchar(80) NOT NULL,
    apellidos varchar(80) NOT NULL,
    dni varchar(12) NOT NULL UNIQUE,
    telefono varchar(20) NOT NULL,
    correo varchar(120) NOT NULL UNIQUE,
    numero_brevete varchar(20) NOT NULL,
    turno_habitual varchar(10) NOT NULL CHECK (turno_habitual IN ('MANANA', 'TARDE', 'COMPLETO')),
    estado_registro varchar(10) NOT NULL CHECK (estado_registro IN ('ACTIVO', 'INACTIVO')),
    id_usuario_seguridad uuid UNIQUE,
    estado_vinculacion varchar(15) NOT NULL CHECK (estado_vinculacion IN ('PENDIENTE', 'VINCULADO')),
    vinculado_en timestamptz,
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    creado_por uuid NOT NULL,
    actualizado_por uuid NOT NULL
);

CREATE TABLE furgonetas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    placa varchar(10) NOT NULL UNIQUE,
    estado varchar(20) NOT NULL CHECK (estado IN ('DISPONIBLE', 'EN_MANTENIMIENTO', 'INACTIVA')),
    capacidad_max_kg numeric(10,3) NOT NULL CHECK (capacidad_max_kg > 0),
    capacidad_max_m3 numeric(10,4) NOT NULL CHECK (capacidad_max_m3 > 0),
    max_paquetes smallint NOT NULL CHECK (max_paquetes > 0),
    creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now(),
    creado_por uuid NOT NULL,
    actualizado_por uuid NOT NULL
);

CREATE TABLE asignaciones_diarias (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repartidor_id uuid NOT NULL REFERENCES repartidores(id),
    furgoneta_id uuid NOT NULL REFERENCES furgonetas(id),
    zona_id_externa uuid NOT NULL,
    fecha_jornada date NOT NULL,
    capacidad_max_kg numeric(10,3) NOT NULL CHECK (capacidad_max_kg > 0),
    capacidad_max_m3 numeric(10,4) NOT NULL CHECK (capacidad_max_m3 > 0),
    max_paquetes smallint NOT NULL CHECK (max_paquetes > 0),
    estado varchar(10) NOT NULL CHECK (estado IN ('ACTIVA', 'CERRADA')),
    abierta_en timestamptz NOT NULL DEFAULT now(),
    abierta_por uuid NOT NULL,
    cerrada_en timestamptz,
    tipo_cierre varchar(25) CHECK (tipo_cierre IN ('REPARTIDOR', 'CORTE_AUTOMATICO', 'GESTOR_DESPACHO', 'MANTENIMIENTO')),
    CONSTRAINT ck_jornada_cierre CHECK ((estado = 'ACTIVA' AND cerrada_en IS NULL)
        OR (estado = 'CERRADA' AND cerrada_en IS NOT NULL))
);
CREATE UNIQUE INDEX uq_jornada_repartidor_activo
    ON asignaciones_diarias(repartidor_id, fecha_jornada) WHERE estado = 'ACTIVA';
CREATE UNIQUE INDEX uq_jornada_furgoneta_activa
    ON asignaciones_diarias(furgoneta_id, fecha_jornada) WHERE estado = 'ACTIVA';

CREATE TRIGGER trg_repartidores_actualizado_en BEFORE UPDATE ON repartidores
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
CREATE TRIGGER trg_furgonetas_actualizado_en BEFORE UPDATE ON furgonetas
    FOR EACH ROW EXECUTE FUNCTION public.set_actualizado_en();
