package org.campuslab.audit.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.campuslab.audit.dto.BookingEventEnvelope;
import org.campuslab.audit.exception.InvalidAuditEventException;
import org.campuslab.audit.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Interpreta los mensajes de Kafka tal como los publica ms-campuslab-bookings. */
class BookingEventConsumerTest {

    /**
     * Mensaje REAL: salida del JsonSerializer de ms-campuslab-bookings (ver EventEnvelopeWireFormatTest en ese repo).
     * Ojo con el timestamp: viaja como segundos decimales, no como texto ISO.
     */
    private static final String WIRE_FORMAT = """
            {"type":"BOOKING_APROBADA","eventId":"4d08738a-65c7-403b-a30a-caafffce2d71","timestamp":1792384344.653502000,
             "traceId":"4d08738a-65c7-403b-a30a-caafffce2d71","correlationId":"4d08738a-65c7-403b-a30a-caafffce2d71",
             "payload":{"bookingId":7,"resourceId":3,"status":"APROBADA","actorEmail":"tec@duocuc.cl",
             "actorName":"Rodrigo Muñoz","actorRole":"TECNICO","clientIp":"200.1.2.3"}}""";

    // El mismo ObjectMapper que arma Spring Boot
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final AuditEventService service = mock(AuditEventService.class);
    private final BookingEventConsumer consumer = new BookingEventConsumer(objectMapper, service);

    @Test
    void interpretaElMensajeRealDeBookingsYLoRegistra() {
        consumer.consume(record(WIRE_FORMAT));

        ArgumentCaptor<BookingEventEnvelope> captor = ArgumentCaptor.forClass(BookingEventEnvelope.class);
        verify(service).record(captor.capture(), eq("bookings.events"));
        BookingEventEnvelope envelope = captor.getValue();
        assertThat(envelope.eventId()).isEqualTo("4d08738a-65c7-403b-a30a-caafffce2d71");
        assertThat(envelope.timestamp()).isEqualTo(Instant.ofEpochSecond(1792384344L, 653_502_000));
        assertThat(envelope.payload().bookingId()).isEqualTo(7L);
        assertThat(envelope.payload().status()).isEqualTo("APROBADA");
        assertThat(envelope.payload().actorEmail()).isEqualTo("tec@duocuc.cl");
        assertThat(envelope.payload().actorRole()).isEqualTo("TECNICO");
        assertThat(envelope.payload().clientIp()).isEqualTo("200.1.2.3");
    }

    @Test
    void tambienEntiendeUnTimestampEnTextoIso() {
        consumer.consume(record("""
                {"eventId":"e1","timestamp":"2026-10-19T03:15:00Z","payload":{"bookingId":1,"status":"SOLICITADA"}}"""));

        ArgumentCaptor<BookingEventEnvelope> captor = ArgumentCaptor.forClass(BookingEventEnvelope.class);
        verify(service).record(captor.capture(), eq("bookings.events"));
        assertThat(captor.getValue().timestamp()).isEqualTo(Instant.parse("2026-10-19T03:15:00Z"));
    }

    @Test
    void ignoraCamposNuevosQueAunNoConoce() {
        consumer.consume(record("""
                {"eventId":"e2","timestamp":1.0,"campoNuevo":true,"payload":{"bookingId":1,"status":"APROBADA","otroCampo":"x"}}"""));

        verify(service).record(any(), eq("bookings.events"));
    }

    @Test
    void unMensajeQueNoEsJsonSeRechazaSinReintentos() {
        assertThatThrownBy(() -> consumer.consume(record("{esto no es json")))
                .isInstanceOf(InvalidAuditEventException.class)
                .hasMessageContaining("bookings.events-0@5");
        verify(service, never()).record(any(), any());
    }

    @Test
    void unMensajeNuloSeRechazaSinReintentos() {
        assertThatThrownBy(() -> consumer.consume(record(null))).isInstanceOf(InvalidAuditEventException.class);
        verify(service, never()).record(any(), any());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("bookings.events", 0, 5L, "7", value);
    }
}
