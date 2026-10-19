package org.campuslab.audit.controller;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.campuslab.audit.dto.AuditEventResponse;
import org.campuslab.audit.service.AuditEventService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de SOLO LECTURA del historial de auditoría (el único modo de escribir es el consumidor de Kafka).
 *
 * <pre>
 *  GET /api/audit/events?type=&actor=&bookingId=&from=&to=&limit=   hechos, del más reciente al más antiguo
 *  GET /api/audit/bookings/{bookingId}/events                      timeline de una reserva, del más antiguo al más reciente
 * </pre>
 *
 * Fechas en ISO-8601 con zona (2026-10-19T03:00:00Z). {@code from} es inclusivo y {@code to} exclusivo.
 * Solo los roles ADMIN y AUDITOR pueden consultar.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
public class AuditController {

    private static final String DEFAULT_LIMIT = "200";

    private final AuditEventService service;

    @GetMapping("/events")
    public List<AuditEventResponse> events(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Long bookingId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = DEFAULT_LIMIT) int limit) {
        return service.search(type, actor, bookingId, from, to, limit);
    }

    @GetMapping("/bookings/{bookingId}/events")
    public List<AuditEventResponse> timeline(@PathVariable Long bookingId) {
        return service.timeline(bookingId);
    }
}
