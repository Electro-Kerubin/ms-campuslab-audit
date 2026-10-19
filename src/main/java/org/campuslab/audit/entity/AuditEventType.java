package org.campuslab.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Catálogo de tipos de evento auditable (BOOKING_CREATED, BOOKING_APPROVED...). Ver V1 y V2. */
@Entity
@Table(name = "audit_event_types")
@Getter
@Setter
@NoArgsConstructor
public class AuditEventType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "description", length = 200)
    private String description;
}
