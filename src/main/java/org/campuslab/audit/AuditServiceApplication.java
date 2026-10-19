package org.campuslab.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Audit Service Application
 *
 * Punto de entrada para el microservicio de auditoría.
 *
 * Responsabilidades:
 * - Consumir los eventos de reserva de Kafka (bookings.events)
 * - Persistir el timeline de eventos en PostgreSQL, sin duplicados (idempotente)
 * - Exponer endpoints de solo lectura para consultar el historial (/api/audit/**)
 * - Garantizar trazabilidad: quién, qué, cuándo y desde dónde
 */
// Sin @ComponentScan explícito: @SpringBootApplication ya escanea este paquete y el scan manual
// rompería los filtros de los tests de slice (@WebMvcTest).
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }

}
