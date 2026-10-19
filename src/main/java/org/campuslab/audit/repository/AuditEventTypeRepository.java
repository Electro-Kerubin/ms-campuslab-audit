package org.campuslab.audit.repository;

import java.util.Optional;

import org.campuslab.audit.entity.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventTypeRepository extends JpaRepository<AuditEventType, Short> {

    Optional<AuditEventType> findByCode(String code);
}
