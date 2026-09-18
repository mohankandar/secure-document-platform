package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.exception.IntakeExpiredException;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.UUID;

/**
 * Global exception handler. Returns safe JSON errors without leaking internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);


    @ExceptionHandler(LicenseEnforcementException.class)
    public ResponseEntity<ApiErrorResponse> handleLicenseEnforcement(LicenseEnforcementException ex,
                                                                      HttpServletRequest request) {
        HttpStatus status = ex.serviceUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
        StructuredLogContext.warn(log, "LICENSE_ENFORCEMENT_REJECTED",
                Map.of("reasonCode", ex.errorCode(), "licenseStatus", ex.status().name()),
                "Tenant license rejected the requested operation");
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        StructuredLogContext.warn(log, "REQUEST_VALIDATION_REJECTED",
                Map.of("reasonCode", ex.errorCode()), "Request validation rejected");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationId));
    }

    @ExceptionHandler(IntakeExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleIntakeExpired(IntakeExpiredException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(IntakeConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIntakeConflict(IntakeConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ex.errorCode(), ex.getMessage(), correlationIdForError(request)));
    }

    @ExceptionHandler(SecurityContextException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurityContext(SecurityContextException ex,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("SECURITY_CONTEXT_INVALID", "Authenticated identity is not authorized for this tenant/application",
                        correlationIdForError(request)));
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("REQUEST_BODY_INVALID path={} correlation={}",
                request == null ? "unknown" : request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_REQUEST_BODY", "Request body is malformed or contains an invalid value type", correlationId));
    }

    @ExceptionHandler(TechnicalDependencyException.class)
    public ResponseEntity<ApiErrorResponse> handleTechnicalDependency(TechnicalDependencyException ex,
                                                                       HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        log.warn("TECHNICAL_DEPENDENCY_FAILED path={} dependency={} code={} retryable={} attempts={} correlation={}",
                request == null ? "unknown" : request.getRequestURI(), ex.dependency(), ex.failureCode(),
                ex.retryable(), ex.attempts(), correlationId);
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ex.failureCode().name(), ex.getMessage(), correlationId));
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
        StructuredLogContext.error(log, "UNHANDLED_EXCEPTION",
                Map.of("reasonCode", ex.getClass().getSimpleName()), "Unhandled exception");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred",
                        correlationIdForError(request)));
    }

    private String correlationIdForError(HttpServletRequest request) {
        if (request != null) {
            Object established = request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
            if (established instanceof UUID uuid) return uuid.toString();
        }
        return UUID.randomUUID().toString();
    }
}
