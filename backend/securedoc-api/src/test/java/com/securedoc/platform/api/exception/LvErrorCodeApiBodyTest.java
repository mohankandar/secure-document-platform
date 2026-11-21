package com.securedoc.platform.api.exception;

import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.domain.error.CodedArgumentException;
import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Task 0025 step 5: a sample per area of the API error body: {@code code} is the LV code, {@code message} its title,
 * {@code errorCode} today's internal identifier; the HTTP status is as before.
 */
class LvErrorCodeApiBodyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/test");
    }

    private static void body(ResponseEntity<ApiErrorResponse> response, HttpStatus status, String errorCode,
                             LvErrorCode code, String message) {
        assertEquals(status, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(errorCode, body.errorCode());
        assertEquals(code.code(), body.code());
        assertEquals(message, body.message());
        assertNotNull(body.correlationId());
        assertNotNull(body.timestamp());
    }

    @Test
    void intake() {
        body(handler.handleValidation(new IntakeValidationException(IntakeRejectionCode.UNKNOWN_IDENTIFIER,
                        "Unknown business identifier: loan"), request()),
                HttpStatus.BAD_REQUEST, "INTAKE_UNKNOWN_IDENTIFIER", LvErrorCode.INT_014, "Unknown case field");
        body(handler.handleIntakeConflict(new IntakeConflictException("SOURCE_REVISION_REUSED", "reused"), request()),
                HttpStatus.CONFLICT, "SOURCE_REVISION_REUSED", LvErrorCode.INT_018, "Same revision sent with different content");
    }

    @Test
    void scanner() {
        body(handler.handleValidation(new ValidationException(LvErrorCode.SCN_003, "Scanner metadata is missing siteId"),
                request()), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", LvErrorCode.SCN_003, "Scanner details missing");
    }

    @Test
    void processingAiRedactionAndExport() {
        body(handler.handleTechnicalDependency(new TechnicalDependencyException(TechnicalDependency.OCR,
                        TechnicalFailureCode.OCR_UNAVAILABLE, false, "OCR unavailable"), request()),
                HttpStatus.BAD_GATEWAY, "OCR_UNAVAILABLE", LvErrorCode.PRC_005, "Text reader not available");
        body(handler.handleTechnicalDependency(new TechnicalDependencyException(TechnicalDependency.AI,
                        TechnicalFailureCode.AI_TIMEOUT, true, "AI timeout"), request()),
                HttpStatus.SERVICE_UNAVAILABLE, "AI_TIMEOUT", LvErrorCode.AI_002, "AI model timed out");
        body(handler.handleTechnicalDependency(new TechnicalDependencyException(TechnicalDependency.REDACTION,
                        TechnicalFailureCode.REDACTION_TEXT_LAYER_REMAINS, false, "verification"), request()),
                HttpStatus.BAD_GATEWAY, "REDACTION_TEXT_LAYER_REMAINS", LvErrorCode.RED_002,
                "Redacted copy failed the safety check");
        body(handler.handleTechnicalDependency(new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                        TechnicalFailureCode.DELIVERY_IO, true, "busy").withLvCode(LvErrorCode.DLV_002), request()),
                HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_IO", LvErrorCode.DLV_002, "Destination busy or down");
    }

    @Test
    void review() {
        body(handler.handleIllegalState(new CodedStateException(LvErrorCode.REV_002, "Cannot approve task in status: X"),
                request()), HttpStatus.CONFLICT, "INVALID_STATE", LvErrorCode.REV_002, "Case is no longer in review");
        body(handler.handleIllegalArgument(new CodedArgumentException(LvErrorCode.REV_006, "Left must be 0.0-1.0"),
                request()), HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", LvErrorCode.REV_006, "Box not valid");
    }

    @Test
    void signInAndAccess() {
        body(handler.handleSecurityContext(new SecurityContextException("no role", "NO_MAPPED_ROLE"), request()),
                HttpStatus.FORBIDDEN, "SECURITY_CONTEXT_INVALID", LvErrorCode.AUT_002, "No access to LexaVault");
        body(handler.handleSecurityContext(new SecurityContextException("lookup", "NO_ACTIVE_CONNECTION"), request()),
                HttpStatus.FORBIDDEN, "SECURITY_CONTEXT_INVALID", LvErrorCode.AUT_007, "Sign-in isn't working right now");
        body(handler.handleAccessDenied(new AccessDeniedException("Insufficient permissions"), request()),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED", LvErrorCode.AUT_006, "Not allowed");
    }

    @Test
    void licence() {
        body(handler.handleLicenseEnforcement(new LicenseEnforcementException("LICENSE_EXPIRED", "Tenant license has expired",
                        LicenseValidationStatus.EXPIRED), request()),
                HttpStatus.FORBIDDEN, "LICENSE_EXPIRED", LvErrorCode.LIC_002, "Licence expired");
    }

    @Test
    void settingsNameTheFieldAndRange() {
        body(handler.handleValidation(new ValidationException(LvErrorCode.CFG_001,
                        "Automatic redaction threshold must be between 0.50 and 0.99"), request()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", LvErrorCode.CFG_001,
                "Setting not valid: Automatic redaction threshold must be between 0.50 and 0.99");
        // Only LV-CFG-001 carries the detail; any other code is its title alone.
        body(handler.handleValidation(new ValidationException(LvErrorCode.CFG_006,
                        "A routing rule already exists for this marker and scan region"), request()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", LvErrorCode.CFG_006, "Split rule not valid");
    }

    @Test
    void systemAndTheFallback() {
        body(handler.handleGeneric(new RuntimeException("secret text"), request()),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", LvErrorCode.SYS_001, "Something went wrong");
        body(handler.handleValidation(new ValidationException("a refusal with no code yet"), request()),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", LvErrorCode.SYS_001, "Something went wrong");
        body(handler.handleIllegalState(new IllegalStateException("library text"), request()),
                HttpStatus.CONFLICT, "INVALID_STATE", LvErrorCode.SYS_001, "Something went wrong");
    }

    @Test
    void everyEnterpriseIdentityAdmissionReasonHasASignInCode() {
        for (IdentityAdmissionException.Reason reason : IdentityAdmissionException.Reason.values()) {
            LvErrorCode code = LvErrorCodes.forDetail(reason.name()).orElseThrow(() ->
                    new AssertionError("no LV code for admission reason " + reason));
            assertEquals(LvErrorCode.Area.AUT, code.area(), reason.name());
        }
        assertNotEquals(LvErrorCode.SYS_001, LvErrorCodes.forAdmissionReason("AMBIGUOUS_SELECTION"));
    }
}
