package org.campuslab.audit.entity;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un hecho auditable: quién hizo qué sobre una reserva, cuándo y desde dónde.
 * Es de solo escritura para el consumidor de Kafka y de solo lectura para la API.
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** envelope.eventId del mensaje de Kafka; es único, así un mensaje repetido no se guarda dos veces. */
    @Column(name = "event_id", nullable = false, unique = true, length = 64, updatable = false)
    private String eventId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "event_type_id", nullable = false)
    private AuditEventType eventType;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** Recurso (sala/equipo) de la reserva, en ms-campuslab-catalog. */
    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    /** ADMIN, TECNICO, ESTUDIANTE o AUDITOR; nulo si la operación no tuvo un usuario identificable. */
    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /** Momento en que ocurrió el hecho (lo fija el productor, no este servicio). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "source_topic", nullable = false, length = 60)
    private String sourceTopic;

    /** Datos propios del hecho (estado nuevo, estudiante dueño de la reserva, horario). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    /** Momento en que este servicio lo recibió. */
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private Instant receivedAt;
}
