package org.campuslab.audit.repository;

import java.util.List;

import org.campuslab.audit.entity.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    /** Para la idempotencia: ¿ya se guardó el evento con este eventId? */
    boolean existsByEventId(String eventId);

    /** Timeline de una reserva, del hecho más antiguo al más reciente. */
    List<AuditEvent> findByBookingIdOrderByOccurredAtAscIdAsc(Long bookingId, Pageable pageable);
}
