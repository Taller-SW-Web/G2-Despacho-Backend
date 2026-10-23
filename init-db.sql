-- Solo para el primer arranque de PostgreSQL local con un volumen vacío.
-- CREATE DATABASE usa template1: estas bases quedan sin tablas de negocio.
-- Flyway de cada microservicio aplica después sus propias migraciones.
CREATE DATABASE despacho_gestion;
CREATE DATABASE despacho_operacion;
