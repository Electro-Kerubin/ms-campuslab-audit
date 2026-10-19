package org.campuslab.audit.config;

import org.apache.kafka.common.TopicPartition;
import org.campuslab.audit.exception.InvalidAuditEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Qué pasa cuando el consumidor no puede procesar un mensaje (CONTEXT.md: "*.DLT por consumidor").
 *
 * <pre>
 *  Situación                                           Decisión                                 Destino
 *  --------------------------------------------------  ---------------------------------------  ------------------
 *  Mensaje ilegible o incompleto (InvalidAuditEvent)   sin reintentos                           bookings.events.DLT
 *  Falla transitoria (base de datos, red)              reintentos con backoff exponencial;      bookings.events.DLT
 *                                                      si se agotan, se manda a la DLT
 *  Procesado correctamente / evento repetido           se confirma (offset avanza)              -
 * </pre>
 *
 * Un mensaje en la DLT no se pierde: queda para inspeccionarlo y reprocesarlo; el consumidor sigue con el siguiente.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<?, ?> kafkaOperations,
            @Value("${campuslab.audit.retry.max-attempts:5}") int maxAttempts,
            @Value("${campuslab.audit.retry.initial-interval-ms:1000}") long initialIntervalMs,
            @Value("${campuslab.audit.retry.multiplier:2.0}") double multiplier) {

        // La DLT se llama <tópico>.DLT; partición -1 = que Kafka elija, así no depende del número de particiones
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

        // maxAttempts cuenta el primer intento; el back-off cuenta solo los reintentos
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, maxAttempts - 1));
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("Mensaje enviado a la DLT: {}-{}@{} ({})",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
            recoverer.accept(record, exception);
        }, backOff);
        // Reintentar un mensaje que nunca va a ser válido solo retrasa su llegada a la DLT
        handler.addNotRetryableExceptions(InvalidAuditEventException.class);
        return handler;
    }
}
