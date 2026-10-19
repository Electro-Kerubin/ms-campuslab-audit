package org.campuslab.audit.exception;

/**
 * El mensaje de Kafka no se puede convertir en un evento de auditoría (JSON ilegible, falta un campo
 * obligatorio, estado desconocido). Reintentarlo no lo arregla: va directo a la DLT.
 */
public class InvalidAuditEventException extends RuntimeException {

    public InvalidAuditEventException(String message) {
        super(message);
    }

    public InvalidAuditEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
