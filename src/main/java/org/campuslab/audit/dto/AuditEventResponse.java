package org.campuslab.audit.dto;

import java.time.Instant;
import java.util.Map;

/** Un hecho auditable tal como lo devuelve la API de lectura. */
public record AuditEventResponse(
        Long id,
        String eventId,
        /** Código del tipo de evento, p. ej. BOOKING_APPROVED. */
        String type,
        String typeDescription,
        Long bookingId,
        Long resourceId,
        String actorEmail,
        String actorName,
        String actorRole,
        String clientIp,
        Instant occurredAt,
        String traceId,
        String correlationId,
        Map<String, Object> details
) {
}
