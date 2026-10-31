-- V1: esquema original de Servicio Logístico / Gestión de Despachos.
-- Primera migración de una base nueva; historia unificada para validación local.
-- Conserva las 10 tablas, tipos, restricciones y triggers del script original recibido.
-- Requiere PostgreSQL con PostGIS disponible y permisos para instalar la extensión.
-- No ejecutar sobre Supabase actual: sus tablas ya existen.
-- La creación del esquema extensions permite usar el original fuera de Supabase.

CREATE SCHEMA IF NOT EXISTS extensions;

-- PostGIS para las columnas geom (zonas y distrito)
create extension if not exists postgis with schema extensions;

-- Mantiene actualizado_en al día en cada UPDATE
create or replace function public.set_actualizado_en()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.actualizado_en := now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------
-- zonas
-- ---------------------------------------------------------------------
create table public.zonas (
  id                  uuid primary key default gen_random_uuid(),
  nombre              text not null,
  estado              text not null,
  origen_delimitacion text,
  geom                extensions.geometry(Geometry, 4326),
  creado_en           timestamptz not null default now(),
  actualizado_en      timestamptz not null default now(),
  creado_por          uuid,
  actualizado_por     uuid
);

create index idx_zonas_geom on public.zonas using gist (geom);

-- ---------------------------------------------------------------------
-- distrito
-- ---------------------------------------------------------------------
create table public.distrito (
  id             uuid primary key default gen_random_uuid(),
  nombre         text not null,
  provincia      text,
  departamento   text,
  ubigeo         text unique,
  geom           extensions.geometry(Geometry, 4326),
  creado_en      timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);

create index idx_distrito_geom on public.distrito using gist (geom);

-- ---------------------------------------------------------------------
-- zonas_distritos
-- ---------------------------------------------------------------------
create table public.zonas_distritos (
  id             uuid primary key default gen_random_uuid(),
  zona_id        uuid not null references public.zonas (id),
  distrito_id    uuid not null references public.distrito (id),
  codigo_postal  text,
  creado_en      timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),   -- agregada
  unique (zona_id, distrito_id)
);

create index idx_zonas_distritos_distrito on public.zonas_distritos (distrito_id);

-- ---------------------------------------------------------------------
-- zona_conflictos_log
-- ---------------------------------------------------------------------
create table public.zona_conflictos_log (
  id                uuid primary key default gen_random_uuid(),
  nombre_intentado  text,
  zona_conflicto_id uuid references public.zonas (id),
  motivo            text,
  detalle           text,
  intentado_en      timestamptz not null default now(),
  intentado_por     uuid,
  creado_en         timestamptz not null default now(),   -- agregada
  actualizado_en    timestamptz not null default now()    -- agregada
);

create index idx_zona_conflictos_log_zona on public.zona_conflictos_log (zona_conflicto_id);

-- ---------------------------------------------------------------------
-- tarifas_zona
-- ---------------------------------------------------------------------
create table public.tarifas_zona (
  id                   uuid primary key default gen_random_uuid(),
  zona_id              uuid not null references public.zonas (id),
  tarifa_base          numeric(12,2) not null,
  peso_base_kg         numeric(10,2),
  recargo_kg_adicional numeric(12,2),
  factor_volumetrico   numeric(10,2),
  moneda               text,
  plazo_min_dias       integer,
  plazo_max_dias       integer,
  activa               boolean not null default true,
  creado_en            timestamptz not null default now(),
  creado_por           uuid,
  actualizado_en       timestamptz not null default now()    -- agregada
);

create index idx_tarifas_zona_zona on public.tarifas_zona (zona_id);

-- ---------------------------------------------------------------------
-- motivo_fallo
-- ---------------------------------------------------------------------
create table public.motivo_fallo (
  codigo                 text primary key,
  etiqueta               text not null,
  seleccionable_en_campo boolean not null default true,
  consume_intento        boolean not null default true,
  activo                 boolean not null default true,
  creado_en              timestamptz not null default now(),   -- agregada
  actualizado_en         timestamptz not null default now()    -- agregada
);

-- ---------------------------------------------------------------------
-- despachos
-- ---------------------------------------------------------------------
create table public.despachos (
  id                          uuid primary key default gen_random_uuid(),
  pedido_id                   uuid not null,   -- pedido de otro sistema (sin FK)
  zona_id                     uuid references public.zonas (id),
  codigo_rastreo_interno      text unique,
  es_simulado                 boolean not null default false,
  estado                      text not null,
  destinatario_nombre         text,
  destinatario_telefono       text,
  direccion                   text,
  referencia                  text,
  distrito                    text,
  codigo_postal               text,
  latitud                     numeric(9,6),
  longitud                    numeric(9,6),
  peso_kg                     numeric(10,2),
  volumen_m3                  numeric(10,3),
  cantidad_paquetes           integer,
  fecha_comprometida          date,
  fecha_programada            date,
  en_cola_desde               timestamptz,
  repartidor_id_externo       uuid,   -- repartidores.id de la BD 1
  asignacion_diaria_id_externa uuid,  -- asignaciones_diarias.id de la BD 1
  reserva_capacidad_id_externa uuid,  -- reservas_capacidad.id de la BD 1
  secuencia_ruta              integer,
  numero_intentos             integer not null default 0,
  pedido_anulado_en           timestamptz,
  version                     integer not null default 1,
  creado_en                   timestamptz not null default now(),
  actualizado_en              timestamptz not null default now()
);

create index idx_despachos_pedido on public.despachos (pedido_id);
create index idx_despachos_zona   on public.despachos (zona_id);
create index idx_despachos_estado on public.despachos (estado);

-- ---------------------------------------------------------------------
-- historial_estados_despacho
-- ---------------------------------------------------------------------
create table public.historial_estados_despacho (
  id                  uuid primary key default gen_random_uuid(),
  despacho_id         uuid not null references public.despachos (id),
  tipo_operacion      text not null,
  estado_anterior     text,
  estado_nuevo        text not null,
  funcionalidad_origen text,
  ejecutor_tipo       text,
  ejecutor_id         text,
  origen              text,
  motivo_codigo       text,
  observaciones       text,
  datos               jsonb,
  registrado_en       timestamptz not null default now(),
  creado_en           timestamptz not null default now(),   -- agregada
  actualizado_en      timestamptz not null default now()    -- agregada
);

create index idx_historial_estados_despacho_despacho on public.historial_estados_despacho (despacho_id);

-- ---------------------------------------------------------------------
-- intentos_entrega
-- ---------------------------------------------------------------------
create table public.intentos_entrega (
  id                           uuid primary key default gen_random_uuid(),
  despacho_id                  uuid not null references public.despachos (id),
  asignacion_diaria_id_externa uuid,   -- asignaciones_diarias.id de la BD 1
  repartidor_id_externo        uuid,   -- repartidores.id de la BD 1
  resultado                    text not null,
  motivo_codigo                text references public.motivo_fallo (codigo),
  consume_intento              boolean not null default true,
  comentario                   text,
  nombre_receptor              text,
  evidencia_id_externa         uuid,   -- evidencias.id de la BD 1
  origen                       text,
  registrado_en                timestamptz not null default now(),
  recibido_en_centro_en        timestamptz,
  recibido_por                 uuid,
  sello_intacto                boolean,
  observacion_recepcion        text,
  creado_en                    timestamptz not null default now(),   -- agregada
  actualizado_en               timestamptz not null default now()    -- agregada
);

create index idx_intentos_entrega_despacho on public.intentos_entrega (despacho_id);
create index idx_intentos_entrega_motivo   on public.intentos_entrega (motivo_codigo);

-- ---------------------------------------------------------------------
-- eventos_salientes
-- ---------------------------------------------------------------------
create table public.eventos_salientes (
  id                uuid primary key default gen_random_uuid(),
  despacho_id       uuid not null references public.despachos (id),
  destino           text not null,
  tipo_evento       text not null,
  payload           jsonb not null,
  estado_envio      text not null,
  intentos_envio    integer not null default 0,
  proximo_intento_en timestamptz,
  ultimo_error      text,
  creado_en         timestamptz not null default now(),
  enviado_en        timestamptz,
  actualizado_en    timestamptz not null default now()    -- agregada
);

create index idx_eventos_salientes_despacho on public.eventos_salientes (despacho_id);
create index idx_eventos_salientes_pendientes on public.eventos_salientes (estado_envio, proximo_intento_en);

-- ---------------------------------------------------------------------
-- Triggers: actualizado_en automático
-- ---------------------------------------------------------------------
create trigger trg_zonas_actualizado_en
  before update on public.zonas
  for each row execute function public.set_actualizado_en();

create trigger trg_distrito_actualizado_en
  before update on public.distrito
  for each row execute function public.set_actualizado_en();

create trigger trg_zonas_distritos_actualizado_en
  before update on public.zonas_distritos
  for each row execute function public.set_actualizado_en();

create trigger trg_zona_conflictos_log_actualizado_en
  before update on public.zona_conflictos_log
  for each row execute function public.set_actualizado_en();

create trigger trg_tarifas_zona_actualizado_en
  before update on public.tarifas_zona
  for each row execute function public.set_actualizado_en();

create trigger trg_motivo_fallo_actualizado_en
  before update on public.motivo_fallo
  for each row execute function public.set_actualizado_en();

create trigger trg_despachos_actualizado_en
  before update on public.despachos
  for each row execute function public.set_actualizado_en();

create trigger trg_historial_estados_despacho_actualizado_en
  before update on public.historial_estados_despacho
  for each row execute function public.set_actualizado_en();

create trigger trg_intentos_entrega_actualizado_en
  before update on public.intentos_entrega
  for each row execute function public.set_actualizado_en();

create trigger trg_eventos_salientes_actualizado_en
  before update on public.eventos_salientes
  for each row execute function public.set_actualizado_en();
