package org.campuslab.audit.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.ServletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

/**
 * Convierte las excepciones en respuestas JSON con el mismo formato que el resto de los microservicios:
 * {"timestamp", "status", "message"}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Filtro inválido (limit fuera de rango, tipo desconocido, from posterior a to) -> 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Parámetro con formato inválido (una fecha mal escrita, un bookingId no numérico) -> 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "El parámetro '" + ex.getName() + "' tiene un formato inválido");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "Falta el parámetro '" + ex.getParameterName() + "'");
    }

    // @PreAuthorize rechazó el rol -> 403 (sin este handler caería en el 500 genérico)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "Solo los roles ADMIN y AUDITOR pueden consultar la auditoría");
    }

    // Errores de la capa web que Spring ya clasifica con su propio código (ruta inexistente 404, verbo no
    // permitido 405, tipo de contenido 415...). Sin este handler el genérico de abajo los convertiría en 500.
    @ExceptionHandler(ServletException.class)
    public ResponseEntity<Map<String, Object>> handleWebError(ServletException ex) {
        HttpStatusCode status = ex instanceof ErrorResponse errorResponse ? errorResponse.getStatusCode() : HttpStatus.BAD_REQUEST;
        String message = switch (status.value()) {
            case 404 -> "Ruta no encontrada";
            case 405 -> "Método no permitido: la auditoría es de solo lectura";
            case 415 -> "Tipo de contenido no soportado";
            default -> "Petición inválida";
        };
        return build(HttpStatus.valueOf(status.value()), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Error inesperado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
