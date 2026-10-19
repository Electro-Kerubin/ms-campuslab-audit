-- V3: los eventos que publica ms-campuslab-bookings identifican al actor por correo, nombre y rol del JWT de
-- Azure AD (no por un id numérico de app_users) e incluyen la IP de origen y el recurso reservado.
-- Cambios aditivos sobre V1/V2; el id numérico del actor queda como columna opcional.

ALTER TABLE audit_events ALTER COLUMN actor_user_id DROP NOT NULL;
-- Una operación sin usuario identificable (evento del sistema) queda con rol nulo
ALTER TABLE audit_events ALTER COLUMN actor_role DROP NOT NULL;

ALTER TABLE audit_events
    ADD COLUMN actor_email VARCHAR(255),
    ADD COLUMN actor_name  VARCHAR(255),
    ADD COLUMN client_ip   VARCHAR(45),   -- IPv4 o IPv6 de quien hizo la petición
    ADD COLUMN resource_id BIGINT;        -- recurso (sala/equipo) de la reserva, en ms-campuslab-catalog

CREATE INDEX idx_audit_events_actor_email ON audit_events (actor_email);
