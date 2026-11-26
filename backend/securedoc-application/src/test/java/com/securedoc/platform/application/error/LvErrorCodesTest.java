package com.securedoc.platform.application.error;

import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.exception.IntakeExpiredException;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.exception.TenantBoundaryDeniedException;
import com.securedoc.platform.application.exception.TenantInactiveException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.model.IntakeFailure;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import com.securedoc.platform.domain.tenant.Capability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0025 steps 2 and 5: every internal identifier has a code; anything else is LV-SYS-001. */
class LvErrorCodesTest {

    @Test
    void everyTechnicalFailureCodeHasACode() {
        for (TechnicalFailureCode code : TechnicalFailureCode.values()) {
            LvErrorCode lv = LvErrorCodes.forTechnicalFailure(code);
            assertNotNull(lv, code.name());
            assertFalse(lv.reserved(), code.name());
        }
        assertEquals(TechnicalFailureCode.values().length, LvErrorCodes.technicalFailureTable().size());
        // The one code fixed before task 0025 keeps its code.
        assertEquals("LV-DLV-003", LvErrorCodes.forTechnicalFailure(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED).code());
        assertEquals(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED.publicCode(),
                LvErrorCodes.forTechnicalFailure(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED).code());
    }

    @Test
    void everyIntakeRejectionCodeHasACode() {
        for (IntakeRejectionCode code : IntakeRejectionCode.values()) {
            assertNotNull(LvErrorCodes.forIntakeRejection(code), code.name());
            assertFalse(LvErrorCodes.forIntakeRejection(code).reserved(), code.name());
            assertEquals(LvErrorCodes.forIntakeRejection(code),
                    LvErrorCodes.forDetail("INTAKE_" + code.name()).orElseThrow(), code.name());
        }
    }

    @Test
    void everyLicenceStatusAndCodeHasACode() {
        for (LicenseValidationStatus status : LicenseValidationStatus.values()) {
            assertEquals(LvErrorCode.Area.LIC, LvErrorCodes.forLicenseStatus(status).area(), status.name());
        }
        for (String code : List.of("LICENSE_REQUIRED", "LICENSE_EXPIRED", "LICENSE_NOT_YET_VALID",
                "LICENSE_TENANT_MISMATCH", "LICENSE_INVALID_SIGNATURE", "LICENSE_UNSUPPORTED_SCHEMA", "LICENSE_MALFORMED",
                "LICENSE_CONFIGURATION_ERROR", "LICENSE_NOT_PERMITTED")) {
            assertEquals(LvErrorCode.Area.LIC, LvErrorCodes.forDetail(code).orElseThrow().area(), code);
        }
    }

    @Test
    void everyPollingCategoryHasACode() {
        for (String category : List.of("POLL_SOURCE_UNAVAILABLE", "POLL_INTERRUPTED", "POLL_DISCOVERY_FAILED",
                "RETRIEVAL_FAILED", "POLL_RESPONSE_INVALID", "RETRIEVAL_INVALID_CONTENT_TYPE", "RETRIEVAL_STAGING_FAILED", "RETRIEVAL_TOO_LARGE",
                "SOURCE_ACKNOWLEDGEMENT_FAILED", "ACK_SERIALIZATION_FAILED", "POLL_ALREADY_RUNNING",
                "POLL_AUTH_CONFIGURATION_ERROR", "POLL_AUTH_CONFIGURATION_UNSUPPORTED", "POLL_CONFIGURATION_ERROR")) {
            assertTrue(LvErrorCodes.forDetail(category).isPresent(), category);
        }
        assertEquals(LvErrorCode.INT_040, LvErrorCodes.forPollingCategory("POLL_SOURCE_UNAVAILABLE"));
    }

    @Test
    void noMappedCodeIsReservedAndTheTableNeverPointsAtANullCode() {
        LvErrorCodes.detailTable().forEach((key, code) -> {
            assertNotNull(code, key);
            assertFalse(code.reserved(), key);
        });
    }

    @Test
    void anUnmappedFailureIsSomethingWentWrong() {
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.of(new RuntimeException("x")));
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.of(new ValidationException("no code given")));
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.forDetailOrFallback("SOMETHING_NEW", "test"));
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.forAdmissionReason("UNSPECIFIED"));
        assertTrue(LvErrorCodes.forDetail(null).isEmpty());
    }

    @Test
    void aFailureKeepsItsOwnCodeFirstThenItsType() {
        assertEquals(LvErrorCode.CFG_001, LvErrorCodes.of(new ValidationException(LvErrorCode.CFG_001, "range")));
        assertEquals(LvErrorCode.REV_002, LvErrorCodes.of(new CodedStateException(LvErrorCode.REV_002, "moved on")));
        assertEquals(LvErrorCode.REV_002,
                LvErrorCodes.of(new RuntimeException("wrapper", new CodedStateException(LvErrorCode.REV_002, "x"))));
        assertEquals(LvErrorCode.INT_014, LvErrorCodes.of(
                new IntakeValidationException(IntakeRejectionCode.UNKNOWN_IDENTIFIER, "unknown")));
        assertEquals(LvErrorCode.INT_018, LvErrorCodes.of(new IntakeConflictException("SOURCE_REVISION_REUSED", "x")));
        assertEquals(LvErrorCode.INT_023, LvErrorCodes.of(new IntakeExpiredException("x")));
        assertEquals(LvErrorCode.INT_025, LvErrorCodes.of(new NotFoundException("DocumentPackage", "id")));
        // Task 0026: anything not found that is not a case is LV-SYS-008 "Item not found".
        assertEquals(LvErrorCode.SYS_008, LvErrorCodes.of(new NotFoundException("RoutingRule", "id")));
        assertEquals(LvErrorCode.AUT_006, LvErrorCodes.of(new AccessDeniedException("x")));
        assertEquals(LvErrorCode.AUT_008, LvErrorCodes.of(new AccessDeniedException(LvErrorCode.AUT_008, "x")));
        assertEquals(LvErrorCode.AUT_003, LvErrorCodes.of(new TenantBoundaryDeniedException("x")));
        assertEquals(LvErrorCode.AUT_005, LvErrorCodes.of(new TenantInactiveException("x")));
        assertEquals(LvErrorCode.AUT_010, LvErrorCodes.of(new CapabilityNotEnabledException(Capability.values()[0])));
        assertEquals(LvErrorCode.REV_001, LvErrorCodes.of(new ReviewAlreadyDecidedException()));
        assertEquals(LvErrorCode.LIC_002, LvErrorCodes.of(
                new LicenseEnforcementException("LICENSE_EXPIRED", "x", LicenseValidationStatus.EXPIRED)));
        assertEquals(LvErrorCode.INT_041, LvErrorCodes.of(new PollingSourceException("POLL_DISCOVERY_FAILED", "x")));
    }

    @Test
    void aTechnicalFailureCanCarryAPreciseCode() {
        TechnicalDependencyException busy = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                TechnicalFailureCode.DELIVERY_IO, true, "busy").withLvCode(LvErrorCode.DLV_002);
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.of(busy));
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.of(busy.withAttempts(3)));
        assertEquals(TechnicalFailureCode.DELIVERY_IO, busy.failureCode());
        assertTrue(busy.retryable());
        TechnicalDependencyException plain = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                TechnicalFailureCode.DELIVERY_IO, true, "unreachable");
        assertEquals(LvErrorCode.DLV_001, LvErrorCodes.of(plain));
    }

    @Test
    void stopAuditDetailCarriesTheCodeAndTheDetail() {
        TechnicalDependencyException timeout = new TechnicalDependencyException(TechnicalDependency.OCR,
                TechnicalFailureCode.OCR_TIMEOUT, true, "timeout");
        assertEquals("Document processing failed: code=LV-PRC-004, detail=OCR_TIMEOUT, dependency=OCR, retryable=true, attempts=1",
                TechnicalFailureClassifier.safeAuditDetail(timeout, "Document processing failed"));
        assertEquals("Document processing failed: code=LV-SYS-001, detail=UNKNOWN_TECHNICAL_FAILURE, dependency=UNKNOWN, retryable=false, attempts=1",
                TechnicalFailureClassifier.safeAuditDetail(new RuntimeException("x"), "Document processing failed"));
        assertEquals("code=LV-INT-040, detail=POLL_SOURCE_UNAVAILABLE, exceptionType=X",
                LvErrorCodes.auditDetail(LvErrorCode.INT_040, "POLL_SOURCE_UNAVAILABLE", "exceptionType=X"));
        assertEquals("code=LV-SYS-001", LvErrorCodes.auditDetail(null, null, null));
    }

    @Test
    void intakeFailureKeepsTheCode() {
        assertEquals(LvErrorCode.INT_014, IntakeFailure.of(
                new IntakeValidationException(IntakeRejectionCode.UNKNOWN_IDENTIFIER, "x")).lvCode());
        assertEquals(LvErrorCode.INT_007, IntakeFailure.of(
                new ValidationException(LvErrorCode.INT_007, "not a PDF")).lvCode());
        assertEquals(LvErrorCode.SYS_001, IntakeFailure.of(new RuntimeException("x")).lvCode());
        assertEquals(LvErrorCode.INT_040, new IntakeFailure("POLL_SOURCE_UNAVAILABLE", "X").lvCode());
    }

    @Test
    void aSampleCodePerArea() {
        assertEquals(LvErrorCode.INT_001, LvErrorCodes.forDetail("INVALID_REQUEST_BODY").orElseThrow());
        assertEquals(LvErrorCode.SCN_002, LvErrorCodes.forDetail("SCANNER_SOURCE_SIZE_MISMATCH").orElseThrow());
        assertEquals(LvErrorCode.PRC_008, LvErrorCodes.forDetail("PROCESSING_RECOVERY_EXHAUSTED").orElseThrow());
        assertEquals(LvErrorCode.AI_002, LvErrorCodes.forDetail("AI_TIMEOUT").orElseThrow());
        assertEquals(LvErrorCode.RED_002, LvErrorCodes.forDetail("REDACTION_TEXT_LAYER_REMAINS").orElseThrow());
        assertEquals(LvErrorCode.REV_001, LvErrorCodes.forDetail("REVIEW_ALREADY_DECIDED").orElseThrow());
        assertEquals(LvErrorCode.DLV_012, LvErrorCodes.forDetail("ROUTING_MARKER_AMBIGUOUS").orElseThrow());
        assertEquals(LvErrorCode.AUT_002, LvErrorCodes.forAdmissionReason("NO_MAPPED_ROLE"));
        assertEquals(LvErrorCode.LIC_001, LvErrorCodes.forDetail("LICENSE_REQUIRED").orElseThrow());
        assertEquals(LvErrorCode.CFG_003, LvErrorCodes.forDetail("ADDRESS_NOT_ALLOWED").orElseThrow());
        assertEquals(LvErrorCode.SYS_002, LvErrorCodes.forDetail("STORAGE_IO").orElseThrow());
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.forDetail("LV-DLV-002").orElseThrow());
    }

    // ---------- task 0026 ----------

    @Test
    void task0026ResumedDropThatNoLongerMatchesItsCaseIsInt035() {
        assertEquals(LvErrorCode.INT_035, LvErrorCodes.forIntakeRejection(IntakeRejectionCode.RECOVERY_CONFLICT));
        assertEquals(LvErrorCode.INT_035, LvErrorCodes.forDetail("INTAKE_RECOVERY_CONFLICT").orElseThrow());
    }

    @Test
    void task0026NotFoundIsCaseOrItemOrTheThrowersOwnCode() {
        assertEquals(LvErrorCode.INT_025, LvErrorCodes.of(new NotFoundException("ExternalIntake", "id")));
        assertEquals(LvErrorCode.SYS_008, LvErrorCodes.of(new NotFoundException("IntegrationConnection", "id")));
        assertEquals(LvErrorCode.SYS_008, LvErrorCodes.optional(new NotFoundException("Artifact", "id")).orElseThrow());
        // #355, #363: the original missing at processing or redaction.
        assertEquals(LvErrorCode.PRC_007, LvErrorCodes.of(new NotFoundException("Original artifact for document",
                "id", LvErrorCode.PRC_007)));
        assertEquals(LvErrorCode.PRC_007, TechnicalFailureClassifier.classify(new RuntimeException("wrapped",
                new NotFoundException("Original artifact for document", "id", LvErrorCode.PRC_007))).lvCode());
    }

    @Test
    void task0026UnknownAddressAndWrongMethodAreSys009() {
        assertEquals(LvErrorCode.SYS_009, LvErrorCodes.forDetail("NO_SUCH_ENDPOINT").orElseThrow());
        assertEquals(LvErrorCode.SYS_009, LvErrorCodes.forDetail("METHOD_NOT_ALLOWED").orElseThrow());
    }

    @Test
    void task0026DatabaseAndQueueFullByType() {
        assertEquals(LvErrorCode.SYS_003, LvErrorCodes.forPlatformFailure(new java.sql.SQLException("down")));
        assertEquals(LvErrorCode.SYS_003, LvErrorCodes.forPlatformFailure(
                new RuntimeException("wrapped", new java.sql.SQLTransientConnectionException("down"))));
        assertEquals(LvErrorCode.SYS_004, LvErrorCodes.forPlatformFailure(
                new java.util.concurrent.RejectedExecutionException("full")));
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.forPlatformFailure(new IllegalStateException("other")));
        assertEquals(LvErrorCode.SYS_001, LvErrorCodes.forPlatformFailure(null));
    }

    @Test
    void task0026NoAiSettingsForTheEntityIsAi006() {
        TechnicalDependencyException noProfile = new TechnicalDependencyException(TechnicalDependency.AI,
                TechnicalFailureCode.AI_UNAVAILABLE, false, "No AI provider profile is configured for this tenant")
                .withLvCode(LvErrorCode.AI_006);
        assertEquals(LvErrorCode.AI_006, LvErrorCodes.of(noProfile));
        assertEquals(LvErrorCode.AI_006, TechnicalFailureClassifier.classify(noProfile).lvCode());
        // The internal code stays AI_UNAVAILABLE (the detail).
        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, noProfile.failureCode());
    }

    @Test
    void task0026EmptyLicenceTokenIsLic001() {
        assertEquals(LvErrorCode.LIC_001, LvErrorCodes.of(new ValidationException(LvErrorCode.LIC_001,
                "Signed license token is required")));
    }
}
