package org.campuslab.audit.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Envelope común de los mensajes del proyecto (CONTEXT.md): type, eventId, timestamp, traceId, correlationId + payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEventEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingEventPayload payload
) {
}
