-- Tipos de evento de auditoría alineados a la máquina de estados de reservas
-- y a los hitos de entrega/recepción de equipos.
INSERT INTO audit_event_types (code, description) VALUES
    ('BOOKING_CREATED',       'Reserva solicitada por el estudiante'),
    ('BOOKING_APPROVED',      'Reserva aprobada por el técnico'),
    ('BOOKING_PREP_STARTED',  'Sala/equipo en preparación'),
    ('BOOKING_IN_USE',        'Reserva marcada como en uso'),
    ('BOOKING_RETURNED',      'Equipo/sala devuelto'),
    ('BOOKING_CANCELLED',     'Reserva cancelada'),
    ('EQUIPMENT_DELIVERED',   'Equipo entregado al estudiante'),
    ('EQUIPMENT_RECEIVED',    'Equipo recibido de vuelta por el técnico');
