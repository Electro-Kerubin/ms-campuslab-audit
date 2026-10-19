package org.campuslab.audit.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.campuslab.audit.dto.AuditEventResponse;
import org.campuslab.audit.dto.BookingEventEnvelope;
import org.campuslab.audit.dto.BookingEventPayload;
import org.campuslab.audit.entity.AuditEvent;
import org.campuslab.audit.entity.AuditEventType;
import org.campuslab.audit.exception.InvalidAuditEventException;
import org.campuslab.audit.repository.AuditEventRepository;
import org.campuslab.audit.repository.AuditEventTypeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra los hechos auditables que llegan por Kafka y los consulta.
 *
 * - Escritura idempotente: un eventId ya guardado se ignora (Kafka entrega "al menos una vez").
 * - Lectura: filtros combinables por tipo, actor, reserva y rango de fechas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditEventService {

    /** Tope de resultados por consulta, para que una consulta sin filtros no cargue toda la tabla. */
    public static final int MAX_LIMIT = 1000;

    // Roles que acepta la restricción CHECK de audit_events.actor_role
    private static final Set<String> KNOWN_ROLES = Set.of("ADMIN", "TECNICO", "ESTUDIANTE", "AUDITOR");

    private final AuditEventRepository events;
    private final AuditEventTypeRepository types;

    // ---------- Escritura ----------

    /**
     * Guarda el hecho que describe el evento.
     *
     * @return true si se guardó; false si ya estaba guardado (mensaje repetido)
     * @throws InvalidAuditEventException si el evento no se puede interpretar (no tiene sentido reintentar)
     */
    @Transactional
    public boolean record(BookingEventEnvelope envelope, String sourceTopic) {
        validate(envelope);
        BookingEventPayload payload = envelope.payload();

        if (events.existsByEventId(envelope.eventId())) {
            log.debug("Evento {} ya registrado, se ignora", envelope.eventId());
            return false;
        }

        String code = BookingStatusMapper.eventCodeFor(payload.status())
                .orElseThrow(() -> new InvalidAuditEventException("Estado de reserva desconocido: " + payload.status()));
        AuditEventType type = types.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("El tipo de evento " + code + " no está en audit_event_types"));

        AuditEvent event = new AuditEvent();
        event.setEventId(envelope.eventId());
        event.setEventType(type);
        event.setBookingId(payload.bookingId());
        event.setResourceId(payload.resourceId());
        event.setActorEmail(blankToNull(payload.actorEmail()));
        event.setActorName(blankToNull(payload.actorName()));
        event.setActorRole(normalizeRole(payload.actorRole()));
        event.setClientIp(blankToNull(payload.clientIp()));
        event.setOccurredAt(envelope.timestamp());
        // Si el productor no manda trazabilidad se usa el eventId, igual que hace ms-campuslab-bookings
        event.setTraceId(orDefault(envelope.traceId(), envelope.eventId()));
        event.setCorrelationId(orDefault(envelope.correlationId(), envelope.eventId()));
        event.setSourceTopic(sourceTopic);
        event.setDetails(detailsOf(payload));
        events.save(event);

        log.info("Auditoría: {} reserva={} actor={} ip={}", code, payload.bookingId(), payload.actorEmail(), payload.clientIp());
        return true;
    }

    private void validate(BookingEventEnvelope envelope) {
        if (envelope == null) throw new InvalidAuditEventException("Mensaje vacío");
        if (isBlank(envelope.eventId())) throw new InvalidAuditEventException("El evento no trae eventId");
        if (envelope.timestamp() == null) throw new InvalidAuditEventException("El evento no trae timestamp");
        if (envelope.payload() == null) throw new InvalidAuditEventException("El evento no trae payload");
        if (envelope.payload().bookingId() == null) throw new InvalidAuditEventException("El evento no trae bookingId");
        if (isBlank(envelope.payload().status())) {
            throw new InvalidAuditEventException("El evento no trae el estado de la reserva");
        }
    }

    private Map<String, Object> detailsOf(BookingEventPayload payload) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", payload.status());
        details.put("studentEmail", payload.studentEmail());
        details.put("startTime", payload.startTime());
        details.put("endTime", payload.endTime());
        return details;
    }

    // ---------- Lectura ----------

    /**
     * Busca hechos con filtros opcionales y combinables, del más reciente al más antiguo.
     *
     * @param typeCode código exacto del tipo (BOOKING_APPROVED...)
     * @param actor    texto contenido en el correo o el nombre de quien hizo la acción
     * @param from     ocurrido en este instante o después
     * @param to       ocurrido antes de este instante
     */
    @Transactional(readOnly = true)
    public List<AuditEventResponse> search(String typeCode, String actor, Long bookingId, Instant from, Instant to, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit debe estar entre 1 y " + MAX_LIMIT);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from debe ser anterior a to");
        }
        if (!isBlank(typeCode) && types.findByCode(typeCode.trim()).isEmpty()) {
            throw new IllegalArgumentException("Tipo de evento desconocido: " + typeCode);
        }

        Specification<AuditEvent> filters = (root, query, cb) -> {
            Predicate all = cb.conjunction();
            if (!isBlank(typeCode)) {
                all = cb.and(all, cb.equal(root.get("eventType").get("code"), typeCode.trim()));
            }
            if (!isBlank(actor)) {
                String pattern = "%" + escapeLike(actor.trim().toLowerCase(Locale.ROOT)) + "%";
                all = cb.and(all, cb.or(
                        cb.like(cb.lower(root.get("actorEmail")), pattern, '\\'),
                        cb.like(cb.lower(root.get("actorName")), pattern, '\\')));
            }
            if (bookingId != null) {
                all = cb.and(all, cb.equal(root.get("bookingId"), bookingId));
            }
            if (from != null) {
                all = cb.and(all, cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                all = cb.and(all, cb.lessThan(root.get("occurredAt"), to));
            }
            return all;
        };

        Sort newestFirst = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return events.findAll(filters, PageRequest.of(0, limit, newestFirst)).getContent().stream()
                .map(this::toResponse)
                .toList();
    }

    /** Historia completa de una reserva, del primer hecho al último. */
    @Transactional(readOnly = true)
    public List<AuditEventResponse> timeline(Long bookingId) {
        return events.findByBookingIdOrderByOccurredAtAscIdAsc(bookingId, PageRequest.of(0, MAX_LIMIT)).stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(), e.getEventId(), e.getEventType().getCode(), e.getEventType().getDescription(),
                e.getBookingId(), e.getResourceId(),
                e.getActorEmail(), e.getActorName(), e.getActorRole(), e.getClientIp(),
                e.getOccurredAt(), e.getTraceId(), e.getCorrelationId(), e.getDetails());
    }

    // ---------- utilidades ----------

    private static String normalizeRole(String role) {
        if (isBlank(role)) return null;
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return KNOWN_ROLES.contains(upper) ? upper : null;
    }

    // Un '%' o '_' escrito por el usuario debe buscarse literalmente, no actuar como comodín
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String blankToNull(String text) {
        return isBlank(text) ? null : text.trim();
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
