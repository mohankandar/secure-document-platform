package com.securedoc.platform.api.exception;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.api.security.AccessDeniedAuditRecorder;
import com.securedoc.platform.api.security.CapabilityAuthorizationInterceptor;
import com.securedoc.platform.api.security.SecurityContextRefusalRecorder;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import com.securedoc.platform.api.upload.RequestBodyTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Global exception handler. Returns safe JSON errors without leaking internals.
 *
 * <p>Task 0023: no exception message that can hold content reaches the response or the log. Where the message is
 * not written by LexaVault for the person (an {@link IllegalStateException}, an {@link IllegalArgumentException},
 * anything unhandled), the response carries a fixed text per error code and the log carries the exception type, the
 * error code, the route pattern, the correlation id and the code location it came from ({@code origin}: class,
 * method and line of the nearest LexaVault frame), never the message.
 *
 * <p>Task 0025: every body carries {@code code}, the LexaVault error code, and {@code message} is that code's title
 * (for {@code LV-CFG-001}, "Setting not valid", followed by the field and allowed range LexaVault's own message gives).
 * {@code errorCode} stays the internal identifier (API version 1). HTTP statuses are unchanged. A failure without a
 * code gets {@code LV-SYS-001} and the gap is logged with its type ({@code LV_CODE_UNMAPPED}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** B5: the fixed message for every {@code INVALID_STATE}, whatever the exception said. */
    static final String INVALID_STATE_MESSAGE = "This action is not possible in the current state.";
    static final String SECURITY_CONTEXT_MESSAGE = "Authenticated identity is not authorized for this tenant/application";
    /** Header and parameter names are LexaVault's own; anything else is not echoed. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    /** Records 403s not already recorded (task U022 step 0); absent in plain unit tests. */
    private final AccessDeniedAuditRecorder accessDeniedAuditRecorder;
    /** Logs and audits sign-in admission refusals (task 0023 C5); absent in plain unit tests. */
    private final SecurityContextRefusalRecorder securityContextRefusalRecorder;

    public GlobalExceptionHandler() {
        this(null, null);
    }

    public GlobalExceptionHandler(AccessDeniedAuditRecorder accessDeniedAuditRecorder) {
        this(accessDeniedAuditRecorder, null);
    }

    @Autowired
    public GlobalExceptionHandler(AccessDeniedAuditRecorder accessDeniedAuditRecorder,
                                  SecurityContextRefusalRecorder securityContextRefusalRecorder) {
        this.accessDeniedAuditRecorder = accessDeniedAuditRecorder;
        this.securityContextRefusalRecorder = securityContextRefusalRecorder;
    }

    /** The nearest LexaVault frame ({@code Class.method:line}), else the top frame: code, never data. */
    static String origin(Throwable ex) {
        StackTraceElement[] trace = ex == null ? new StackTraceElement[0] : ex.getStackTrace();
        for (StackTraceElement frame : trace) {
            if (frame.getClassName().startsWith("com.securedoc.")) return frame(frame);
        }
        return trace.length > 0 ? frame(trace[0]) : "unknown";
    }

    private static String frame(StackTraceElement frame) {
        String type = frame.getClassName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private static String route(HttpServletRequest request) {
        return request == null ? "unknown" : CapabilityAuthorizationInterceptor.routePattern(request);
    }

    private static String safeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches() ? name : null;
    }

    private void logRejected(String event, String code, Throwable ex, HttpServletRequest request, String correlationId) {
        log.warn("{} code={} type={} origin={} route={} correlation={}", event, code, ex.getClass().getSimpleName(),
                origin(ex), route(request), correlationId);
    }


    @ExceptionHandler(LicenseEnforcementException.class)
    public ResponseEntity<ApiErrorResponse> handleLicenseEnforcement(LicenseEnforcementException ex,
                                                                      HttpServletRequest request) {
        HttpStatus status = ex.serviceUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
        StructuredLogContext.warn(log, "LICENSE_ENFORCEMENT_REJECTED",
                Map.of("reasonCode", ex.errorCode(), "licenseStatus", ex.status().name()),
                "Tenant license rejected the requested operation");
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationIdForError(request))
                        .withPlatformCode(ex.platformCode().orElse(null), null));
    }

    /**
     * Task 0057: a refusal of the shared platform that reaches a request keeps its own code and its own "what to do",
     * beside LexaVault's code. Its message is never sent or logged.
     */
    private static ApiErrorResponse withPlatform(ApiErrorResponse body, Throwable failure) {
        return com.securedoc.platform.api.error.PlatformRefusals.codeOf(failure)
                .map(code -> body.withPlatformCode(code.code(), code.fix())).orElse(body);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        StructuredLogContext.warn(log, "REQUEST_VALIDATION_REJECTED",
                Map.of("reasonCode", ex.errorCode()), "Request validation rejected");
        LvErrorCode code = LvErrorCodes.of(ex);
        // Task 0027 (owner, 2026-11-21): "support action not possible now" is a state conflict, 409 as the code says.
        HttpStatus status = code == LvErrorCode.CFG_011 ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ex.errorCode(), code, ex.getMessage(), correlationId)
                        .withDetail(validationDetail(ex, code)));
    }

    /**
     * Task 0026: the API {@code detail} of a validation refusal. The thrower's own detail first; otherwise, for an
     * intake, scanner, settings or licence refusal, LexaVault's own refusal text (it names the field and the allowed
     * range). An intake case-field refusal's message can quote what was sent, so it is never used.
     */
    static String validationDetail(ValidationException ex, LvErrorCode code) {
        if (ex.detail() != null) return ex.detail();
        if (ex instanceof com.securedoc.platform.application.exception.IntakeValidationException) return null;
        if (code == null) return null;
        return switch (code.area()) {
            case INT, SCN, CFG, LIC -> ex.getMessage();
            default -> null;
        };
    }

    @ExceptionHandler(IntakeExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleIntakeExpired(IntakeExpiredException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("INTAKE_EXPIRED_REJECTED code={} route={} correlation={}", ex.errorCode(), route(request), correlationId);
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationId));
    }

    /** Task 0067: the safety stop. The sender sends the same request again after the time given. */
    @ExceptionHandler(com.securedoc.platform.application.exception.IntakeBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleIntakeBusy(
            com.securedoc.platform.application.exception.IntakeBusyException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("INTAKE_BUSY_REJECTED code={} route={} correlation={}", ex.errorCode(), route(request), correlationId);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, Integer.toString(ex.retryAfterSeconds()))
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationId));
    }

    @ExceptionHandler(IntakeConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIntakeConflict(IntakeConflictException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("INTAKE_CONFLICT_REJECTED code={} route={} correlation={}", ex.errorCode(), route(request), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationId));
    }

    /**
     * C5 (#239): the person sees the same safe message for every reason; the reason code is logged, and audited as
     * {@code SECURITY_CONTEXT_INVALID} when the entity and the person are known.
     */
    @ExceptionHandler(SecurityContextException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurityContext(SecurityContextException ex,
                                                                   HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        if (securityContextRefusalRecorder != null && request != null) {
            securityContextRefusalRecorder.recordRefusal(request, ex, correlationId);
        } else {
            log.warn("SECURITY_CONTEXT_REFUSED reason={} route={} correlation={} audited=false",
                    ex.reasonCode(), route(request), correlationId);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("SECURITY_CONTEXT_INVALID", LvErrorCodes.forAdmissionReason(ex.reasonCode()),
                        correlationId));
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        // D1 (#32): a request record refused a value while the body was read (RequestValues): its own 400 text.
        for (Throwable cause = ex.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ValidationException refused) return handleValidation(refused, request);
            if (cause instanceof RequestBodyTooLargeException tooLarge) return handleRequestTooLarge(tooLarge, request);
        }
        String correlationId = correlationIdForError(request);
        log.warn("REQUEST_BODY_INVALID route={} correlation={}", route(request), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_REQUEST_BODY", LvErrorCode.INT_001, correlationId));
    }

    @ExceptionHandler(TechnicalDependencyException.class)
    public ResponseEntity<ApiErrorResponse> handleTechnicalDependency(TechnicalDependencyException ex,
                                                                       HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        log.warn("TECHNICAL_DEPENDENCY_FAILED route={} dependency={} code={} retryable={} attempts={} correlation={}",
                route(request), ex.dependency(), ex.failureCode(),
                ex.retryable(), ex.attempts(), correlationId);
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ex.failureCode().name(), LvErrorCodes.of(ex), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBindingValidation(MethodArgumentNotValidException ex,
                                                                     HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("REQUEST_VALIDATION_REJECTED code=VALIDATION_ERROR fields={} route={} correlation={}",
                ex.getBindingResult().getFieldErrorCount(), route(request), correlationId);
        // Task 0026: the detail names the fields (the API's own names), never what was sent in them.
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> safeName(error.getField())).filter(java.util.Objects::nonNull).distinct().sorted()
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", LvErrorCode.INT_003, correlationId)
                        .withDetail(fields.isEmpty() ? null : "Field " + fields + " is missing, blank or too long"));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.info("NOT_FOUND_REJECTED code={} route={} correlation={}", ex.errorCode(), route(request), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        if (accessDeniedAuditRecorder != null) accessDeniedAuditRecorder.recordIfNotYet(request, ex);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationIdForError(request)));
    }

    @ExceptionHandler(CapabilityNotEnabledException.class)
    public ResponseEntity<ApiErrorResponse> handleCapabilityDisabled(CapabilityNotEnabledException ex,
                                                                      HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("CAPABILITY_DISABLED_REJECTED code={} route={} correlation={}", ex.errorCode(), route(request),
                correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCode.AUT_010, correlationId));
    }

    /** #264: the person's entity exists but is not active: 403 with its own code and a fixed text. */
    @ExceptionHandler(com.securedoc.platform.application.exception.TenantInactiveException.class)
    public ResponseEntity<ApiErrorResponse> handleTenantInactive(
            com.securedoc.platform.application.exception.TenantInactiveException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("TENANT_INACTIVE_REJECTED", "TENANT_INACTIVE", ex, request, correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("TENANT_INACTIVE", LvErrorCode.AUT_005, correlationId));
    }

    /** B5: a state refusal LexaVault wrote for the person (fixed text, no content): its message is sent. */
    @ExceptionHandler(com.securedoc.platform.application.exception.PersonFacingStateException.class)
    public ResponseEntity<ApiErrorResponse> handlePersonFacingState(
            com.securedoc.platform.application.exception.PersonFacingStateException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("INVALID_STATE_REJECTED", "INVALID_STATE", ex, request, correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("INVALID_STATE", LvErrorCodes.of(ex), correlationId));
    }

    /**
     * B5 (#291, #383, #167-#172): the response never carries the exception's text, which can come from a library or
     * the data layer. The type and origin are logged so IT can find the rule that refused.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("INVALID_STATE_REJECTED", "INVALID_STATE", ex, request, correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withPlatform(ApiErrorResponse.of("INVALID_STATE", LvErrorCodes.of(ex), correlationId), ex));
    }

    /** #290: the message can echo a value the client sent (UUID or enum parsing), so it is never sent or logged. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                  HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("INVALID_ARGUMENT_REJECTED", "INVALID_ARGUMENT", ex, request, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", argumentCode(ex, request), correlationId));
    }

    /** #293: names the parameter (LexaVault's own name), never the value sent. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        String name = safeName(ex.getName());
        log.warn("PARAMETER_TYPE_REJECTED code=INVALID_ARGUMENT parameter={} route={} correlation={}",
                name == null ? "unknown" : name, route(request), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", argumentCode(null, request), name == null ? null
                        : "Invalid value for request parameter '" + name + "'", correlationId)
                        .withDetail(name == null ? null : "Parameter " + name + " has the wrong type"));
    }

    /** Task 0055: a body that is not a file is over the limit. */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestTooLarge(RequestBodyTooLargeException ex,
                                                                  HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("REQUEST_TOO_LARGE_REJECTED route={} correlation={}", route(request), correlationId);
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiErrorResponse.of("REQUEST_TOO_LARGE", LvErrorCode.SYS_015, correlationId)
                        .withDetail("The request is larger than the limit of " + ex.limit() + " bytes"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                                 HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.warn("UPLOAD_TOO_LARGE_REJECTED code=FILE_TOO_LARGE route={} correlation={}", route(request), correlationId);
        long limit = ex.getMaxUploadSize();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("FILE_TOO_LARGE", LvErrorCode.INT_004, correlationId)
                        .withDetail(limit > 0 ? "The upload is larger than the limit of " + limit + " bytes"
                                : "The upload is larger than the upload limit"));
    }

    /** D1 (#7): a required header is missing (for example {@code Idempotency-Key} or {@code X-Content-SHA256}). */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex,
                                                                 HttpServletRequest request) {
        String name = safeName(ex.getHeaderName());
        return clientMistake("MISSING_HEADER", name == null ? "A required header is missing"
                : "Header " + name + " is missing", ex, request);
    }

    /** D1 (#7): the multipart {@code file} part is missing. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException ex,
                                                               HttpServletRequest request) {
        String name = safeName(ex.getRequestPartName());
        return clientMistake("MISSING_FILE_PART", name == null ? "A required part is missing"
                : "Part " + name + " is missing", ex, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                    HttpServletRequest request) {
        String name = safeName(ex.getParameterName());
        return clientMistake("MISSING_PARAMETER", name == null ? "A required parameter is missing"
                : "Parameter " + name + " is missing", ex, request);
    }

    /** Any other binding refusal (missing path variable, cookie, request attribute). */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestBinding(ServletRequestBindingException ex,
                                                                  HttpServletRequest request) {
        return clientMistake("INVALID_REQUEST", "The request is missing a required value", ex, request);
    }

    /**
     * D1 (#7): wrong Content-Type. 400 as the intake documentation promises for request mistakes; the message lists
     * the types the endpoint accepts (LexaVault's own), never the type sent.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        String supported = ex.getSupportedMediaTypes().stream().map(MediaType::toString)
                .collect(Collectors.joining(", "));
        return clientMistake("UNSUPPORTED_MEDIA_TYPE", supported.isBlank()
                ? "The Content-Type is not one this endpoint accepts"
                : "Content-Type must be one of: " + supported, ex, request);
    }

    /** D1: a multipart endpoint called with a body that is not a readable multipart request. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        return clientMistake("INVALID_MULTIPART_REQUEST", "The body must be a readable multipart upload", ex, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                     HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("REQUEST_REJECTED", "METHOD_NOT_ALLOWED", ex, request, correlationId);
        // Task 0026: LV-SYS-009; the detail lists the methods the address accepts (LexaVault's own).
        var methods = ex.getSupportedHttpMethods();
        String allowed = methods == null ? ""
                : methods.stream().map(Object::toString).sorted().collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrorResponse.of("METHOD_NOT_ALLOWED",
                        LvErrorCodes.forDetailOrFallback("METHOD_NOT_ALLOWED", "api"), correlationId)
                        .withDetail(allowed.isEmpty() ? "This address doesn't accept this method"
                                : "This address accepts only " + allowed));
    }

    /**
     * Task 0026: an address LexaVault doesn't offer is {@code LV-SYS-009} with the standard body. Spring gives
     * {@link NoResourceFoundException} (the default resource handler) or {@link NoHandlerFoundException} (when resource
     * mappings are off); both are handled. The address itself is not echoed.
     */
    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNoResource(Exception ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.info("NOT_FOUND_REJECTED code=NOT_FOUND lvCode={} type={} correlation={}", LvErrorCode.SYS_009.code(),
                ex.getClass().getSimpleName(), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("NOT_FOUND", LvErrorCodes.forDetailOrFallback("NO_SUCH_ENDPOINT", "api"),
                        correlationId).withDetail("No endpoint at this address"));
    }

    /** Task 0026: {@code detail} names what is wrong (the header, part or parameter), never a value sent. */
    private ResponseEntity<ApiErrorResponse> clientMistake(String code, String detail, Exception ex,
                                                           HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        logRejected("REQUEST_REJECTED", code, ex, request, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(code, LvErrorCodes.forDetailOrFallback(code, "api"), correlationId)
                        .withDetail(detail));
    }

    /** #331: an ApplicationException not handled above: its code is logged with type and origin. */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(ApplicationException ex,
                                                                        HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        log.error("APPLICATION_EXCEPTION code={} type={} origin={} route={} correlation={}", ex.errorCode(),
                ex.getClass().getSimpleName(), origin(ex), route(request), correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ex.errorCode(), LvErrorCodes.of(ex), correlationId));
    }

    /** #331: the log now names where the fault happened (origin) and the request's correlation id. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        String correlationId = correlationIdForError(request);
        // Task 0026: a database failure is LV-SYS-003 and a full work queue LV-SYS-004 (by type); else LV-SYS-001.
        LvErrorCode code = LvErrorCodes.forPlatformFailure(ex);
        StructuredLogContext.error(log, "UNHANDLED_EXCEPTION",
                Map.of("reasonCode", ex.getClass().getSimpleName(), "component", origin(ex)),
                "Unhandled exception route=" + route(request) + " correlation=" + correlationId + " code=" + code.code());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withPlatform(ApiErrorResponse.of("INTERNAL_ERROR", code, correlationId), ex));
    }

    /**
     * The code for a refused argument (#290, #293): the thrower's own code when it has one (for example a box that
     * is not valid, LV-REV-006); a value on an intake route is LV-INT-003 (#3, #6); otherwise LV-CFG-001.
     */
    static LvErrorCode argumentCode(Throwable ex, HttpServletRequest request) {
        if (ex instanceof LvCoded coded && coded.lvCode() != null) return coded.lvCode();
        String route = request == null ? "" : route(request);
        if (route.startsWith("/api/v1/packages") || route.startsWith("/api/v1/external-intakes")
                || route.startsWith("/api/v1/scanner-intakes")) {
            return LvErrorCode.INT_003;
        }
        return LvErrorCode.CFG_001;
    }

    private String correlationIdForError(HttpServletRequest request) {
        if (request != null) {
            Object established = request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
            if (established instanceof UUID uuid) return uuid.toString();
        }
        return UUID.randomUUID().toString();
    }

    /** Task 0023 (D5, #160): "already decided by someone else" in review is one code, 409, with a fixed message. */
    @ExceptionHandler(com.securedoc.platform.application.exception.ReviewAlreadyDecidedException.class)
    public ResponseEntity<ApiErrorResponse> handleReviewAlreadyDecided(
            com.securedoc.platform.application.exception.ReviewAlreadyDecidedException ex, HttpServletRequest request) {
        StructuredLogContext.info(log, "REVIEW_ALREADY_DECIDED",
                Map.of("reasonCode", com.securedoc.platform.application.exception.ReviewAlreadyDecidedException.ERROR_CODE),
                "Review decision refused: already decided");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(com.securedoc.platform.application.exception.ReviewAlreadyDecidedException.ERROR_CODE,
                        LvErrorCode.REV_001, correlationIdForError(request)));
    }
}
