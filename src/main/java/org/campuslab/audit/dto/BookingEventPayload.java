package org.campuslab.audit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Datos de la reserva y del actor dentro del evento que publica ms-campuslab-bookings.
 * Los campos del actor (actorEmail, actorName, actorRole, clientIp) pueden venir nulos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEventPayload(
        Long bookingId,
        Long resourceId,
        String studentEmail,
        /** Estado nuevo de la reserva: SOLICITADA, APROBADA, EN_PREPARACION, EN_USO, DEVUELTA o CANCELADA. */
        String status,
        String startTime,
        String endTime,
        String actorEmail,
        String actorName,
        String actorRole,
        String clientIp
) {
}
