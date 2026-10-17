-- Esquema heredado observado: solo estructura, sin datos ni credenciales de producción.
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE TABLE repartidores (
    id uuid PRIMARY KEY, nombres text NOT NULL, apellidos text NOT NULL, dni text UNIQUE NOT NULL,
    telefono text NOT NULL, correo text NOT NULL, numero_brevete text NOT NULL, turno_habitual text NOT NULL,
    usuario_seguridad_id uuid, estado_vinculacion text NOT NULL,
    ultimo_intento_vinculacion timestamptz, ultimo_error_vinculacion text,
    creado_en timestamptz NOT NULL DEFAULT now(), actualizado_en timestamptz NOT NULL DEFAULT now(),
    creado_por uuid NOT NULL, actualizado_por uuid NOT NULL
);
CREATE TABLE furgoneta (
    id uuid PRIMARY KEY, placa text NOT NULL, estado text NOT NULL,
    capacidad_max_kg numeric NOT NULL, capacidad_max_m3 numeric NOT NULL, max_paquetes integer NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT now(), actualizado_en timestamptz NOT NULL DEFAULT now(),
    creado_por uuid NOT NULL, actualizado_por uuid NOT NULL
);
CREATE TABLE asignaciones_diarias (
    id uuid PRIMARY KEY, repartidor_id uuid NOT NULL REFERENCES repartidores(id),
    furgoneta_id uuid NOT NULL REFERENCES furgoneta(id), zona_id_externa uuid NOT NULL,
    fecha_jornada date NOT NULL, capacidad_max_kg numeric NOT NULL, capacidad_max_m3 numeric NOT NULL,
    max_paquetes integer NOT NULL, estado text NOT NULL, abierta_en timestamptz NOT NULL DEFAULT now(),
    abierta_por uuid NOT NULL, creado_en timestamptz NOT NULL DEFAULT now(), actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reservas_capacidad (
    id uuid PRIMARY KEY, despacho_id_externo uuid NOT NULL,
    asignacion_diaria_id uuid NOT NULL REFERENCES asignaciones_diarias(id),
    peso_kg numeric NOT NULL, volumen_m3 numeric NOT NULL, cantidad_paquetes integer NOT NULL,
    estado text NOT NULL, motivo_liberacion text, creado_en timestamptz NOT NULL DEFAULT now(),
    liberado_en timestamptz, version integer NOT NULL DEFAULT 0, actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE proyeccion_despacho (
    id uuid PRIMARY KEY, despacho_id_externo uuid NOT NULL, pedido_id uuid,
    codigo_rastreo_interno text, asignacion_diaria_id uuid REFERENCES asignaciones_diarias(id),
    reserva_capacidad_id uuid, secuencia_ruta integer, estado text NOT NULL,
    destinatario_nombre text, destinatario_telefono text, fecha_programada date, numero_intentos integer,
    creado_en timestamptz NOT NULL DEFAULT now(), actualizado_en timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auditoria_configuracion (
    id uuid PRIMARY KEY, entidad text NOT NULL, entidad_id text NOT NULL, accion text NOT NULL,
    datos_anteriores jsonb, datos_nuevos jsonb, usuario_id uuid NOT NULL,
    registrado_en timestamptz NOT NULL DEFAULT now(), creado_en timestamptz NOT NULL DEFAULT now(),
    actualizado_en timestamptz NOT NULL DEFAULT now()
);
