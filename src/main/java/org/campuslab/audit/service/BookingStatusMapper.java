package org.campuslab.audit.service;

import java.util.Map;
import java.util.Optional;

/**
 * Traduce el estado de una reserva (el vocabulario de ms-campuslab-bookings) al código del tipo de
 * evento auditable (catálogo audit_event_types, ver V2).
 */
final class BookingStatusMapper {

    private static final Map<String, String> STATUS_TO_EVENT_CODE = Map.of(
            "SOLICITADA", "BOOKING_CREATED",
            "APROBADA", "BOOKING_APPROVED",
            "EN_PREPARACION", "BOOKING_PREP_STARTED",
            "EN_USO", "BOOKING_IN_USE",
            "DEVUELTA", "BOOKING_RETURNED",
            "CANCELADA", "BOOKING_CANCELLED");

    private BookingStatusMapper() {
    }

    static Optional<String> eventCodeFor(String status) {
        return status == null ? Optional.empty() : Optional.ofNullable(STATUS_TO_EVENT_CODE.get(status.trim().toUpperCase()));
    }
}
