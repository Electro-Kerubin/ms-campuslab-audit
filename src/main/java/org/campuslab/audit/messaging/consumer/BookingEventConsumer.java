package org.campuslab.audit.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.campuslab.audit.dto.BookingEventEnvelope;
import org.campuslab.audit.exception.InvalidAuditEventException;
import org.campuslab.audit.service.AuditEventService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume los eventos de reserva que publica ms-campuslab-bookings y los registra como hechos auditables.
 *
 * Política de errores (ver KafkaConsumerConfig):
 * - mensaje ilegible o incompleto (InvalidAuditEventException): no se reintenta, va directo a la DLT
 * - cualquier otra falla (p. ej. la base de datos no responde): se reintenta con backoff y luego va a la DLT
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventConsumer {

    /** Valor de audit_events.source_topic para los eventos que llegan por este consumidor. */
    public static final String SOURCE = "bookings.events";

    private final ObjectMapper objectMapper;
    private final AuditEventService auditEventService;

    @KafkaListener(topics = "${campuslab.audit.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) {
        BookingEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), BookingEventEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidAuditEventException(
                    "JSON ilegible en " + record.topic() + "-" + record.partition() + "@" + record.offset(), exception);
        }
        auditEventService.record(envelope, SOURCE);
    }
}
