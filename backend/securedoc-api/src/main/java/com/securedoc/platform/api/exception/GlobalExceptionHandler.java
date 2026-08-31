package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

/**
 * Global exception handler. Returns safe JSON errors without leaking internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    private static final int MAX_CORRELATION_ID_LENGTH = 128;

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("REQUEST_VALIDATION_REJECTED path={} code={} message={} correlation={}",
                request == null ? "unknown" : request.getRequestURI(), ex.errorCode(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationId));
    }

    @ExceptionHandler(SecurityContextException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurityContext(SecurityContextException ex,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("SECURITY_CONTEXT_INVALID", "Authenticated identity is not authorized for this tenant/application",
                        correlationIdForError(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBindingValidation(MethodArgumentNotValidException ex,
                                                                     HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", message, correlationIdForError(request)));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(CapabilityNotEnabledException.class)
    public ResponseEntity<ApiErrorResponse> handleCapabilityDisabled(CapabilityNotEnabledException ex,
                                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("INVALID_STATE", ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", "Invalid request parameter",
                        correlationIdForError(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", "Invalid request parameter",
                        correlationIdForError(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("FILE_TOO_LARGE", "Upload exceeds maximum allowed size",
                        correlationIdForError(request)));
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(ApplicationException ex,
                                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred",
                        correlationIdForError(request)));
    }

    private String correlationIdForError(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        String incoming = request.getHeader(HEADER_CORRELATION_ID);
        if (incoming != null && !incoming.isBlank()) {
            String trimmed = incoming.trim();
            return trimmed.length() <= MAX_CORRELATION_ID_LENGTH
                    ? trimmed
                    : trimmed.substring(0, MAX_CORRELATION_ID_LENGTH);
        }
        return UUID.randomUUID().toString();
    }
}
