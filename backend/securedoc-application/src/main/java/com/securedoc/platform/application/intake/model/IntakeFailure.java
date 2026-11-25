package com.securedoc.platform.application.intake.model;

import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TenantInactiveException;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Task 0023 (B1, C2): what an intake failure may say about itself. Only a stable code and the exception's type
 * (class simple name) are ever stored, audited, logged or shown; the exception's message is never read, because
 * it can hold a source id, an identifier value, a file name or a tenant id.
 */
public record IntakeFailure(String code, String exceptionType,
                            com.securedoc.platform.domain.error.LvErrorCode lvCode) {

    public IntakeFailure {
        Objects.requireNonNull(code);
        Objects.requireNonNull(exceptionType);
        // Task 0025: the LexaVault code; without one it follows from the code (LV-SYS-001 when unknown).
        if (lvCode == null) {
            lvCode = com.securedoc.platform.application.error.LvErrorCodes.forDetailOrFallback(code, "intake");
        }
    }

    public IntakeFailure(String code, String exceptionType) {
        this(code, exceptionType, null);
    }

    /** Reads the throwable as given; callers unwrap their own wrappers first. */
    public static IntakeFailure of(Throwable failure) {
        Objects.requireNonNull(failure);
        return new IntakeFailure(codeOf(failure), failure.getClass().getSimpleName(),
                com.securedoc.platform.application.error.LvErrorCodes.optional(failure).orElse(null));
    }

    /** A stable code: the code the exception already carries, or a fixed category for its type. */
    static String codeOf(Throwable failure) {
        if (failure instanceof PollingSourceException sourceFailure && notBlank(sourceFailure.category())) {
            return sourceFailure.category();
        }
        if (failure instanceof TechnicalDependencyException technical) return technical.failureCode().name();
        if (failure instanceof TenantInactiveException) return "TENANT_INACTIVE";
        if (failure instanceof ApplicationException application && notBlank(application.errorCode())) {
            return application.errorCode();
        }
        if (failure instanceof UncheckedIOException || failure instanceof IOException) return "SOURCE_IO_ERROR";
        if (failure instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (failure instanceof IllegalStateException) return "INVALID_STATE";
        return "INTERNAL_ERROR";
    }

    /** The watched-folder observation reason for a refused drop (the observation column holds this enum's name). */
    public static IntakeRejectionCode rejectionCodeOf(Throwable failure) {
        if (failure instanceof IntakeValidationException intake) return intake.rejectionCode();
        if (failure instanceof LicenseEnforcementException) return IntakeRejectionCode.LICENSE_DENIED;
        if (failure instanceof CapabilityNotEnabledException) return IntakeRejectionCode.CAPABILITY_DISABLED;
        if (failure instanceof TenantInactiveException) return IntakeRejectionCode.TENANT_INACTIVE;
        if (failure instanceof TechnicalDependencyException technical) {
            if (technical.dependency() == TechnicalDependency.KEY_SERVICE) {
                return technical.failureCode() == com.securedoc.platform.application.exception.TechnicalFailureCode.KEY_SERVICE_REFUSED
                        ? IntakeRejectionCode.KEY_SERVICE_REFUSED : IntakeRejectionCode.KEY_SERVICE_UNAVAILABLE;
            }
            return technical.dependency() == TechnicalDependency.STORAGE
                    ? IntakeRejectionCode.STORAGE_UNAVAILABLE : IntakeRejectionCode.INTERNAL_ERROR;
        }
        if (failure instanceof com.securedoc.platform.application.exception.AccessDeniedException) {
            return IntakeRejectionCode.ACCESS_DENIED;
        }
        if (failure instanceof com.securedoc.platform.application.exception.ValidationException
                || failure instanceof IllegalArgumentException) {
            return IntakeRejectionCode.INVALID_FILE;
        }
        if (failure instanceof UncheckedIOException) return IntakeRejectionCode.SOURCE_IO_ERROR;
        return IntakeRejectionCode.INTERNAL_ERROR;
    }

    /** "CODE:ExceptionType", the compact form kept in a polling run's detail. */
    public String compact() {
        return code + ":" + exceptionType;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
