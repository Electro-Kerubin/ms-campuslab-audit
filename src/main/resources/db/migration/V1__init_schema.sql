-- ms-campuslab-audit: esquema inicial (timeline de auditoría, solo lectura para el cliente)
-- Modelo normalizado (3FN). Motor: PostgreSQL.
-- Este servicio consume Kafka (bookings.events, audit.timeline) y no accede a la BD de bookings.

-- Catálogo de tipos de evento auditable (tabla de referencia administrable).
CREATE TABLE audit_event_types (
    id          SMALLSERIAL PRIMARY KEY,
    code        VARCHAR(40) NOT NULL,
    description VARCHAR(200),
    CONSTRAINT uq_audit_event_types_code UNIQUE (code)
);

-- booking_id y actor_user_id referencian bookings.bookings.id / bookings.app_users.id
-- (otro microservicio/BD: sin FK física).
-- event_id = envelope.eventId del mensaje origen; garantiza idempotencia del consumidor.
CREATE TABLE audit_events (
    id             BIGSERIAL PRIMARY KEY,
    event_id       VARCHAR(64) NOT NULL,
    event_type_id  SMALLINT NOT NULL REFERENCES audit_event_types (id),
    booking_id     BIGINT NOT NULL,
    actor_user_id  BIGINT NOT NULL,
    actor_role     VARCHAR(20) NOT NULL
        CHECK (actor_role IN ('ADMIN', 'TECNICO', 'ESTUDIANTE', 'AUDITOR')),
    occurred_at    TIMESTAMPTZ NOT NULL,
    trace_id       VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    source_topic   VARCHAR(60) NOT NULL
        CHECK (source_topic IN ('bookings.events', 'audit.timeline')),
    details        JSONB,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_events_event_id UNIQUE (event_id)
);

CREATE INDEX idx_audit_events_booking_id ON audit_events (booking_id);
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_event_type_id ON audit_events (event_type_id);
