package org.campuslab.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Audit Service Application
 *
 * Punto de entrada para el microservicio de auditoría.
 *
 * Responsabilidades:
 * - Consumir eventos de Kafka (bookings.events, audit.timeline)
 * - Persistir timeline de eventos en PostgreSQL
 * - Exponer endpoints de lectura para consultar historial de una reserva
 * - Filtrar por usuario, fechas y tipo de evento
 * - Garantizar trazabilidad completa (quién, qué, cuándo, desde dónde)
 */
@SpringBootApplication
@ComponentScan(basePackages = "org.campuslab.audit")
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }

}