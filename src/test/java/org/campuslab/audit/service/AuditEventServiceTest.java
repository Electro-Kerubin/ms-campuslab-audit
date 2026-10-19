package org.campuslab.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.campuslab.audit.dto.AuditEventResponse;
import org.campuslab.audit.dto.BookingEventEnvelope;
import org.campuslab.audit.dto.BookingEventPayload;
import org.campuslab.audit.entity.AuditEvent;
import org.campuslab.audit.entity.AuditEventType;
import org.campuslab.audit.exception.InvalidAuditEventException;
import org.campuslab.audit.repository.AuditEventRepository;
import org.campuslab.audit.repository.AuditEventTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

/** Reglas de registro (idempotencia, mapeo de estados, validación) y de consulta del historial. */
class AuditEventServiceTest {

    private static final Instant OCCURRED = Instant.parse("2026-10-19T03:15:00Z");

    private final AuditEventRepository events = mock(AuditEventRepository.class);
    private final AuditEventTypeRepository types = mock(AuditEventTypeRepository.class);
    private AuditEventService service;

    @BeforeEach
    void setUp() {
        service = new AuditEventService(events, types);
        for (String code : List.of("BOOKING_CREATED", "BOOKING_APPROVED", "BOOKING_PREP_STARTED",
                "BOOKING_IN_USE", "BOOKING_RETURNED", "BOOKING_CANCELLED")) {
            when(types.findByCode(code)).thenReturn(Optional.of(type(code)));
        }
    }

    // ---------- Escritura ----------

    @ParameterizedTest
    @CsvSource({
            "SOLICITADA,BOOKING_CREATED",
            "APROBADA,BOOKING_APPROVED",
            "EN_PREPARACION,BOOKING_PREP_STARTED",
            "EN_USO,BOOKING_IN_USE",
            "DEVUELTA,BOOKING_RETURNED",
            "CANCELADA,BOOKING_CANCELLED"})
    void cadaEstadoDeLaReservaSeRegistraConSuTipoDeEvento(String status, String expectedCode) {
        boolean stored = service.record(envelope("evt-1", payload(status)), "bookings.events");

        assertThat(stored).isTrue();
        assertThat(saved().getEventType().getCode()).isEqualTo(expectedCode);
    }

    @Test
    void guardaQuienLoHizoDesdeDondeYCuando() {
        service.record(envelope("evt-2", payload("APROBADA")), "bookings.events");

        AuditEvent event = saved();
        assertThat(event.getEventId()).isEqualTo("evt-2");
        assertThat(event.getBookingId()).isEqualTo(7L);
        assertThat(event.getResourceId()).isEqualTo(3L);
        assertThat(event.getActorEmail()).isEqualTo("tec@duocuc.cl");
        assertThat(event.getActorName()).isEqualTo("Rodrigo Muñoz");
        assertThat(event.getActorRole()).isEqualTo("TECNICO");
        assertThat(event.getClientIp()).isEqualTo("200.1.2.3");
        assertThat(event.getOccurredAt()).isEqualTo(OCCURRED);
        assertThat(event.getSourceTopic()).isEqualTo("bookings.events");
        assertThat(event.getDetails()).containsEntry("status", "APROBADA").containsEntry("studentEmail", "alumno@duocuc.cl");
    }

    @Test
    void unEventoRepetidoNoSeGuardaDosVeces() {
        when(events.existsByEventId("evt-3")).thenReturn(true);

        boolean stored = service.record(envelope("evt-3", payload("APROBADA")), "bookings.events");

        assertThat(stored).isFalse();
        verify(events, never()).save(any());
    }

    @Test
    void elRolSeNormalizaYUnoDesconocidoQuedaNulo() {
        service.record(envelope("evt-4", payloadWithRole("tecnico")), "bookings.events");
        assertThat(saved().getActorRole()).isEqualTo("TECNICO");

        org.mockito.Mockito.clearInvocations(events);
        service.record(envelope("evt-5", payloadWithRole("INVITADO")), "bookings.events");
        assertThat(saved().getActorRole()).isNull();
    }

    @Test
    void unEventoSinActorSeRegistraIgualConActorVacio() {
        BookingEventPayload sinActor = new BookingEventPayload(7L, 3L, "alumno@duocuc.cl", "SOLICITADA",
                "2026-10-20T10:00", "2026-10-20T12:00", null, null, null, null);

        assertThat(service.record(envelope("evt-6", sinActor), "bookings.events")).isTrue();

        assertThat(saved().getActorEmail()).isNull();
        assertThat(saved().getActorRole()).isNull();
        assertThat(saved().getClientIp()).isNull();
    }

    @Test
    void sinTrazabilidadSeUsaElEventId() {
        BookingEventEnvelope sinTraza = new BookingEventEnvelope("BOOKING_APROBADA", "evt-7", OCCURRED, null, " ", payload("APROBADA"));

        service.record(sinTraza, "bookings.events");

        assertThat(saved().getTraceId()).isEqualTo("evt-7");
        assertThat(saved().getCorrelationId()).isEqualTo("evt-7");
    }

    @Test
    void losEventosInutilizablesSeRechazanSinGuardarNada() {
        BookingEventPayload ok = payload("APROBADA");
        List<BookingEventEnvelope> invalid = List.of(
                new BookingEventEnvelope("t", " ", OCCURRED, "x", "x", ok),
                new BookingEventEnvelope("t", "evt", null, "x", "x", ok),
                new BookingEventEnvelope("t", "evt", OCCURRED, "x", "x", null),
                envelope("evt", new BookingEventPayload(null, 3L, "a", "APROBADA", null, null, null, null, null, null)),
                envelope("evt", new BookingEventPayload(7L, 3L, "a", " ", null, null, null, null, null, null)),
                envelope("evt", payload("ESTADO_INVENTADO")));

        for (BookingEventEnvelope envelope : invalid) {
            assertThatThrownBy(() -> service.record(envelope, "bookings.events"))
                    .isInstanceOf(InvalidAuditEventException.class);
        }
        assertThatThrownBy(() -> service.record(null, "bookings.events")).isInstanceOf(InvalidAuditEventException.class);
        verify(events, never()).save(any());
    }

    @Test
    void siFaltaElTipoEnElCatalogoEsUnErrorDeConfiguracionNoDelMensaje() {
        when(types.findByCode("BOOKING_APPROVED")).thenReturn(Optional.empty());

        // IllegalStateException => el consumidor lo reintenta (no es culpa del mensaje)
        assertThatThrownBy(() -> service.record(envelope("evt-8", payload("APROBADA")), "bookings.events"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- Lectura ----------

    @Test
    void laBusquedaDevuelveLosHechosMapeadosALaRespuesta() {
        AuditEvent stored = new AuditEvent();
        stored.setId(10L);
        stored.setEventId("evt-9");
        stored.setEventType(type("BOOKING_APPROVED"));
        stored.setBookingId(7L);
        stored.setActorEmail("tec@duocuc.cl");
        stored.setActorRole("TECNICO");
        stored.setOccurredAt(OCCURRED);
        Page<AuditEvent> page = new PageImpl<>(List.of(stored));
        when(events.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(page);

        List<AuditEventResponse> result = service.search(null, null, null, null, null, 50);

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("BOOKING_APPROVED");
            assertThat(r.actorEmail()).isEqualTo("tec@duocuc.cl");
            assertThat(r.occurredAt()).isEqualTo(OCCURRED);
        });
    }

    @Test
    void losFiltrosInvalidosSeRechazan() {
        assertThatThrownBy(() -> service.search(null, null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null, null, AuditEventService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, OCCURRED, OCCURRED, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> service.search(null, null, null, OCCURRED.plusSeconds(1), OCCURRED, 10))
                .isInstanceOf(IllegalArgumentException.class);
        when(types.findByCode("BOOKING_INVENTADO")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.search("BOOKING_INVENTADO", null, null, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOOKING_INVENTADO");
    }

    // ---------- helpers ----------

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(captor.capture());
        return captor.getValue();
    }

    private static AuditEventType type(String code) {
        AuditEventType type = new AuditEventType();
        type.setCode(code);
        type.setDescription("desc " + code);
        return type;
    }

    private static BookingEventEnvelope envelope(String eventId, BookingEventPayload payload) {
        return new BookingEventEnvelope("BOOKING_X", eventId, OCCURRED, "trace-" + eventId, "corr-" + eventId, payload);
    }

    private static BookingEventPayload payload(String status) {
        return new BookingEventPayload(7L, 3L, "alumno@duocuc.cl", status, "2026-10-20T10:00", "2026-10-20T12:00",
                "tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "200.1.2.3");
    }

    private static BookingEventPayload payloadWithRole(String role) {
        return new BookingEventPayload(7L, 3L, "alumno@duocuc.cl", "APROBADA", null, null, "x@duocuc.cl", "X", role, null);
    }
}
