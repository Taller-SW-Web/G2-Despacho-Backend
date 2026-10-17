#!/bin/sh
# Solo entorno local: lo ejecuta la imagen de PostgreSQL la primera vez que se crea el volumen.
# Crea una base por microservicio y aplica sus migraciones del repositorio en orden de versión.
set -eu

for directorio in /migraciones/*; do
    base=$(basename "$directorio")
    echo "[entorno-local] Creando base $base"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE \"$base\""
    for migracion in $(ls "$directorio"/V*.sql | sort -V); do
        echo "[entorno-local] $base <- $(basename "$migracion")"
        psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$base" -f "$migracion"
    done
done
