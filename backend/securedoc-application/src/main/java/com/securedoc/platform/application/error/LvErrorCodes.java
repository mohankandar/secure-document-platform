package com.securedoc.platform.application.error;

import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.application.exception.IntakeConflictException;
import com.securedoc.platform.application.exception.IntakeExpiredException;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.exception.TenantBoundaryDeniedException;
import com.securedoc.platform.application.exception.TenantInactiveException;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.securedoc.platform.domain.error.LvErrorCode.*;

/**
 * Task 0025 step 2: the one place where LexaVault's internal failure identifiers become LexaVault error codes.
 *
 * <p>Every value of {@link TechnicalFailureCode}, {@link IntakeRejectionCode} and {@link LicenseValidationStatus} has a
 * code (a test fails otherwise), and so does every polling category, sign-in admission reason, licence code, API
 * {@code errorCode}, routing and derived-delivery code and connection-test reason LexaVault writes. A failure that
 * carries its own code ({@link LvCoded}) keeps it. Anything else gets {@link LvErrorCode#FALLBACK} ({@code LV-SYS-001})
 * and the gap is logged with the failure's type ({@code LV_CODE_UNMAPPED}), never its message.
 *
 * <p>The internal identifier stays beside the code as the <em>detail</em> (API {@code errorCode}, audit {@code detail=}).
 */
public final class LvErrorCodes {

    private static final Logger log = LoggerFactory.getLogger(LvErrorCodes.class);

    private static final Map<TechnicalFailureCode, LvErrorCode> TECHNICAL = technical();
    private static final Map<IntakeRejectionCode, LvErrorCode> INTAKE_REJECTION = intakeRejection();
    private static final Map<LicenseValidationStatus, LvErrorCode> LICENSE_STATUS = licenseStatus();
    /** Every other internal identifier, by name: polling, licence, admission, API, routing, connection tests. */
    private static final Map<String, LvErrorCode> DETAIL = detail();

    private LvErrorCodes() {
    }

    // ---------- the enums ----------

    public static LvErrorCode forTechnicalFailure(TechnicalFailureCode code) {
        return code == null ? FALLBACK : TECHNICAL.get(code);
    }

    public static LvErrorCode forIntakeRejection(IntakeRejectionCode code) {
        return code == null ? FALLBACK : INTAKE_REJECTION.get(code);
    }

    public static LvErrorCode forLicenseStatus(LicenseValidationStatus status) {
        LvErrorCode code = status == null ? null : LICENSE_STATUS.get(status);
        return code == null ? LIC_004 : code;
    }

    /** A sign-in admission reason (LexaVault's own or the Enterprise Identity library's {@code Reason} name). */
    public static LvErrorCode forAdmissionReason(String reason) {
        return forDetail(reason).orElseGet(() -> unmapped("admission", reason));
    }

    /** A polling failure category ({@code PollingSourceException} category or the intake failure code). */
    public static LvErrorCode forPollingCategory(String category) {
        return forDetail(category).orElseGet(() -> unmapped("polling", category));
    }

    /**
     * Any internal identifier LexaVault writes as a failure's code: a {@link TechnicalFailureCode} or
     * {@link IntakeRejectionCode} name, {@code INTAKE_<rejection>}, a polling category, a licence code, an admission
     * reason, an API {@code errorCode}, a routing or derived-delivery code, a connection-test reason, or an
     * {@code LV-…} code itself.
     */
    public static Optional<LvErrorCode> forDetail(String detail) {
        if (detail == null || detail.isBlank()) return Optional.empty();
        String key = detail.trim();
        Optional<LvErrorCode> direct = LvErrorCode.fromCode(key);
        if (direct.isPresent()) return direct;
        key = key.toUpperCase(Locale.ROOT);
        LvErrorCode found = DETAIL.get(key);
        if (found != null) return Optional.of(found);
        for (TechnicalFailureCode technical : TechnicalFailureCode.values()) {
            if (technical.name().equals(key)) return Optional.of(TECHNICAL.get(technical));
        }
        String rejection = key.startsWith("INTAKE_") ? key.substring("INTAKE_".length()) : key;
        for (IntakeRejectionCode code : IntakeRejectionCode.values()) {
            if (code.name().equals(rejection)) return Optional.of(INTAKE_REJECTION.get(code));
        }
        return Optional.empty();
    }

    /** As {@link #forDetail}, falling back to {@code LV-SYS-001} with the gap logged. */
    public static LvErrorCode forDetailOrFallback(String detail, String context) {
        return forDetail(detail).orElseGet(() -> unmapped(context, detail));
    }

    // ---------- exceptions ----------

    /**
     * The code for a failure. The failure's own code comes first, then the first typed failure in its cause chain;
     * otherwise {@code LV-SYS-001}, with the gap logged by type.
     */
    public static LvErrorCode of(Throwable failure) {
        LvErrorCode code = find(failure);
        if (code != null) return code;
        return unmapped("exception", failure == null ? "null" : failure.getClass().getSimpleName());
    }

    /** As {@link #of}, but empty instead of the fallback (nothing is logged). */
    public static Optional<LvErrorCode> optional(Throwable failure) {
        return Optional.ofNullable(find(failure));
    }

    private static LvErrorCode find(Throwable failure) {
        int depth = 0;
        for (Throwable current = failure; current != null && depth < 12; current = current.getCause(), depth++) {
            LvErrorCode code = direct(current);
            if (code != null) return code;
            if (current.getCause() == current) break;
        }
        return null;
    }

    private static LvErrorCode direct(Throwable failure) {
        if (failure instanceof LvCoded coded && coded.lvCode() != null) return coded.lvCode();
        if (failure instanceof TechnicalDependencyException technical) return forTechnicalFailure(technical.failureCode());
        if (failure instanceof IntakeValidationException intake) return forIntakeRejection(intake.rejectionCode());
        if (failure instanceof LicenseEnforcementException licence) {
            return forDetail(licence.errorCode()).orElseGet(() -> forLicenseStatus(licence.status()));
        }
        if (failure instanceof CapabilityNotEnabledException) return AUT_010;
        if (failure instanceof TenantInactiveException) return AUT_005;
        if (failure instanceof TenantBoundaryDeniedException) return AUT_003;
        if (failure instanceof ReviewAlreadyDecidedException) return REV_001;
        if (failure instanceof IntakeExpiredException) return INT_023;
        if (failure instanceof IntakeConflictException conflict) return forDetail(conflict.errorCode()).orElse(null);
        // Task 0026: a case is LV-INT-025; anything else not found (a rule, a connection, a file) is LV-SYS-008.
        if (failure instanceof NotFoundException notFound) return notFound.caseNotFound() ? INT_025 : SYS_008;
        if (failure instanceof AccessDeniedException) return AUT_006;
        if (failure instanceof PollingSourceException polling) return forDetail(polling.category()).orElse(null);
        if (failure instanceof ApplicationException application && !"VALIDATION_ERROR".equals(application.errorCode())) {
            return forDetail(application.errorCode()).orElse(null);
        }
        return infrastructure(failure);
    }

    /**
     * Task 0026: failures of the platform underneath, recognised by type name (the application module does not depend
     * on Spring's data or task libraries): the database (LV-SYS-003) and a full work queue (LV-SYS-004).
     */
    static LvErrorCode infrastructure(Throwable failure) {
        for (Class<?> type = failure.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            String name = type.getName();
            if (QUEUE_FULL_TYPES.contains(name)) return SYS_004;
            if (DATABASE_TYPES.contains(name)) return SYS_003;
        }
        return null;
    }

    /** The code a database or queue failure logs with: LV-SYS-003, LV-SYS-004, otherwise LV-SYS-001 (nothing logged). */
    public static LvErrorCode forPlatformFailure(Throwable failure) {
        LvErrorCode code = failure == null ? null : find(failure);
        return code == null ? FALLBACK : code;
    }

    private static final java.util.Set<String> DATABASE_TYPES = java.util.Set.of(
            "java.sql.SQLException",
            // Task 0061: the case field tables.
            "com.documentplatform.fields.jdbc.FieldStoreException",
            "com.documentplatform.fields.values.FieldValuesException",
            "org.springframework.dao.DataAccessException",
            "org.springframework.transaction.TransactionException",
            "jakarta.persistence.PersistenceException",
            "liquibase.exception.LiquibaseException");
    private static final java.util.Set<String> QUEUE_FULL_TYPES = java.util.Set.of(
            "java.util.concurrent.RejectedExecutionException",
            "org.springframework.core.task.TaskRejectedException");

    private static LvErrorCode unmapped(String context, String identifier) {
        log.warn("LV_CODE_UNMAPPED context={} type={} code={}", context, safe(identifier), FALLBACK.code());
        return FALLBACK;
    }

    /** Identifiers are LexaVault's own names; anything else is not echoed into the log. */
    private static String safe(String identifier) {
        return identifier != null && identifier.matches("[A-Za-z0-9_.$\\-]{1,96}") ? identifier : "unrecognised";
    }

    // ---------- audit ----------

    /**
     * The audit detail for a refusal, stop or export failure: {@code code=LV-…, detail=<internal identifier>} and,
     * when given, the rest of the record's metadata. Codes and identifiers only, never content.
     */
    public static String auditDetail(LvErrorCode code, String internalIdentifier, String rest) {
        StringBuilder detail = new StringBuilder("code=").append((code == null ? FALLBACK : code).code());
        if (internalIdentifier != null && !internalIdentifier.isBlank()) {
            detail.append(", detail=").append(internalIdentifier.trim());
        }
        if (rest != null && !rest.isBlank()) detail.append(", ").append(rest.trim());
        return detail.toString();
    }

    // ---------- the tables (also read by the generated frontend file and its test) ----------

    public static Map<TechnicalFailureCode, LvErrorCode> technicalFailureTable() {
        return Collections.unmodifiableMap(TECHNICAL);
    }

    public static Map<IntakeRejectionCode, LvErrorCode> intakeRejectionTable() {
        return Collections.unmodifiableMap(INTAKE_REJECTION);
    }

    public static Map<LicenseValidationStatus, LvErrorCode> licenseStatusTable() {
        return Collections.unmodifiableMap(LICENSE_STATUS);
    }

    /** Every other internal identifier and its code, in a stable order. */
    public static Map<String, LvErrorCode> detailTable() {
        return Collections.unmodifiableMap(DETAIL);
    }

    private static Map<TechnicalFailureCode, LvErrorCode> technical() {
        Map<TechnicalFailureCode, LvErrorCode> m = new EnumMap<>(TechnicalFailureCode.class);
        for (TechnicalFailureCode code : TechnicalFailureCode.values()) {
            m.put(code, switch (code) {
                case AI_TIMEOUT -> AI_002;
                case AI_RATE_LIMITED -> AI_003;
                case AI_UNAVAILABLE -> AI_001;
                case AI_REJECTED -> AI_004;
                case AI_INVALID_RESPONSE -> AI_005;
                case OCR_TIMEOUT -> PRC_004;
                case OCR_UNAVAILABLE -> PRC_005;
                // #110, #111, #371; the page-level causes (#104, #105, #106) carry PRC-006, PRC-002, PRC-003.
                case OCR_PROCESS_FAILED -> PRC_005;
                // #215, #226, #222; busy (#205), unreadable answers (#206 ...) and "not complete" (#207) carry their own.
                case DELIVERY_IO -> DLV_001;
                // #208, #213, #227; refused (#204), sign-in (#203, #214) and folder exists (#201) carry their own.
                case DELIVERY_DESTINATION_INVALID -> DLV_006;
                case DELIVERY_SENSITIVITY_REFUSED -> DLV_003;
                case STORAGE_IO -> SYS_002;
                case KEY_SERVICE_UNAVAILABLE -> SYS_010;
                case KEY_SERVICE_REFUSED -> SYS_011;
                case STORED_FILE_REFUSED -> SYS_012;
                case ERASE_REPLAY_REQUIRED -> SYS_013;
                case ERASE_LOG_UNAVAILABLE -> SYS_014;
                case PROCESSING_DISPATCH_EXHAUSTED -> PRC_008;
                // The licence status at the stop gives LIC-001 to LIC-004; this is the default.
                case PROCESSING_LICENSE_DENIED -> LIC_001;
                case REDACTION_LOCATION_MISSING -> RED_001;
                case REDACTION_SOURCE_PAGES_UNKNOWN, REDACTION_PAGE_COUNT_MISMATCH, REDACTION_TARGETS_NOT_APPLIED,
                     REDACTION_OUTPUT_EMPTY, REDACTION_TEXT_LAYER_REMAINS, REDACTION_ANNOTATIONS_REMAIN,
                     REDACTION_HIDDEN_CONTENT_REMAINS,
                     REDACTION_REGION_NOT_COVERED, REDACTION_OUTPUT_UNPARSABLE,
                     REDACTION_VALUE_LEFT_ON_CHECKED_PAGE -> RED_002;
                // Task 0044: the final check found a redacted value in the output text.
                case REDACTION_VALUE_IN_OUTPUT_TEXT -> RED_005;
                case UNKNOWN_TECHNICAL_FAILURE -> SYS_001;
            });
        }
        return m;
    }

    private static Map<IntakeRejectionCode, LvErrorCode> intakeRejection() {
        Map<IntakeRejectionCode, LvErrorCode> m = new EnumMap<>(IntakeRejectionCode.class);
        for (IntakeRejectionCode code : IntakeRejectionCode.values()) {
            m.put(code, switch (code) {
                case IDENTIFIER_CONFIGURATION_MISSING, IDENTIFIER_CONFIGURATION_INVALID -> CFG_002;
                case MISSING_REQUIRED_IDENTIFIER -> INT_013;
                case UNKNOWN_IDENTIFIER -> INT_014;
                case IDENTIFIER_CONFLICT -> INT_016;
                case INVALID_IDENTIFIER_VALUE -> INT_015;
                case MALFORMED_TRIGGER -> INT_031;
                case MISSING_FILE -> INT_032;
                case UNSUPPORTED_FILE -> INT_007;
                case NO_DOCUMENTS -> INT_033;
                case DUPLICATE_INTAKE -> INT_017;
                case SOURCE_IO_ERROR -> INT_034;
                // The licence code itself (LIC-001 to LIC-004) is in the audit detail; this is the default.
                case LICENSE_DENIED -> LIC_001;
                case CAPABILITY_DISABLED -> AUT_010;
                case TENANT_INACTIVE -> AUT_005;
                case STORAGE_UNAVAILABLE -> SYS_002;
                case DOCUMENT_KEY_UNAVAILABLE -> SYS_010;
                case DOCUMENT_KEY_REFUSED -> SYS_011;
                case INVALID_FILE -> INT_007;
                case ACCESS_DENIED -> CFG_007;
                // Task 0026: a resumed drop that no longer matches its case.
                case RECOVERY_CONFLICT -> INT_035;
                case INTERNAL_ERROR -> SYS_001;
            });
        }
        return m;
    }

    private static Map<LicenseValidationStatus, LvErrorCode> licenseStatus() {
        Map<LicenseValidationStatus, LvErrorCode> m = new EnumMap<>(LicenseValidationStatus.class);
        for (LicenseValidationStatus status : LicenseValidationStatus.values()) {
            m.put(status, switch (status) {
                case MISSING -> LIC_001;
                case EXPIRED, NOT_YET_VALID -> LIC_002;
                case INVALID_SIGNATURE, TENANT_MISMATCH, UNSUPPORTED_SCHEMA, MALFORMED, CONFIGURATION_ERROR -> LIC_003;
                // A valid licence that refuses: it doesn't include the feature.
                case ACTIVE, NOT_ENFORCED -> LIC_004;
            });
        }
        return m;
    }

    private static Map<String, LvErrorCode> detail() {
        Map<String, LvErrorCode> m = new LinkedHashMap<>();
        // API errorCode values written by the exception handlers (#4, #7, #5, #252, #290 ...).
        m.put("INVALID_REQUEST_BODY", INT_001);
        m.put("MISSING_HEADER", INT_002);
        m.put("MISSING_FILE_PART", INT_002);
        m.put("INVALID_MULTIPART_REQUEST", INT_002);
        m.put("UNSUPPORTED_MEDIA_TYPE", INT_002);
        m.put("MISSING_PARAMETER", INT_003);
        m.put("INVALID_REQUEST", INT_003);
        m.put("FILE_TOO_LARGE", INT_004);
        m.put("REQUEST_TOO_LARGE", SYS_015);
        m.put("INVALID_ARGUMENT", CFG_001);
        m.put("AUTHENTICATION_REQUIRED", AUT_001);
        m.put("ACCESS_DENIED", AUT_006);
        m.put("SECURITY_CONTEXT_INVALID", AUT_007);
        m.put("CAPABILITY_DISABLED", AUT_010);
        m.put("TENANT_INACTIVE", AUT_005);
        m.put("REVIEW_ALREADY_DECIDED", REV_001);
        m.put("INTERNAL_ERROR", SYS_001);
        // Task 0026: an address or method LexaVault doesn't offer.
        m.put("NO_SUCH_ENDPOINT", SYS_009);
        m.put("METHOD_NOT_ALLOWED", SYS_009);
        // Intake conflicts and expiry (#15, #16, #20, #21, #24, #25, #28, #51, #52, #74).
        m.put("INTAKE_EXPIRED", INT_023);
        m.put("SOURCE_REVISION_REUSED", INT_018);
        m.put("RETRY_SOURCE_NOT_FOUND", INT_024);
        m.put("SOURCE_DOCUMENT_ID_REUSED", INT_019);
        m.put("INTAKE_ALREADY_COMPLETE", INT_022);
        m.put("INVALID_INTAKE_STATE", INT_022);
        m.put("MANIFEST_CHECKSUM_MISMATCH", INT_011);
        m.put("CONTENT_CHECKSUM_MISMATCH", INT_012);
        m.put("IDEMPOTENCY_CONFLICT", INT_021);
        m.put("SCANNER_SOURCE_SIZE_MISMATCH", SCN_002);
        // Watched folder: the observation that is not a refusal (#37).
        m.put("WAITING_FOR_TRIGGER", INT_030);
        // Polling categories (#63 to #69, #9, #311, #312).
        m.put("POLL_SOURCE_UNAVAILABLE", INT_040);
        m.put("POLL_INTERRUPTED", INT_040);
        m.put("POLL_DISCOVERY_FAILED", INT_041);
        m.put("RETRIEVAL_FAILED", INT_041);
        m.put("POLL_RESPONSE_INVALID", INT_042);
        m.put("RETRIEVAL_INVALID_CONTENT_TYPE", INT_007);
        m.put("RETRIEVAL_STAGING_FAILED", INT_004);
        m.put("RETRIEVAL_TOO_LARGE", INT_004);
        m.put("SOURCE_ACKNOWLEDGEMENT_FAILED", INT_043);
        m.put("ACK_SERIALIZATION_FAILED", INT_043);
        m.put("POLL_ALREADY_RUNNING", INT_044);
        m.put("POLL_AUTH_CONFIGURATION_ERROR", CFG_003);
        m.put("POLL_AUTH_CONFIGURATION_UNSUPPORTED", CFG_003);
        m.put("POLL_CONFIGURATION_ERROR", CFG_003);
        // Licence codes (#261, #287, #308, #325, #358).
        m.put("LICENSE_REQUIRED", LIC_001);
        m.put("LICENSE_EXPIRED", LIC_002);
        m.put("LICENSE_NOT_YET_VALID", LIC_002);
        m.put("LICENSE_TENANT_MISMATCH", LIC_003);
        m.put("LICENSE_INVALID_SIGNATURE", LIC_003);
        m.put("LICENSE_UNSUPPORTED_SCHEMA", LIC_003);
        m.put("LICENSE_MALFORMED", LIC_003);
        m.put("LICENSE_CONFIGURATION_ERROR", LIC_003);
        m.put("LICENSE_NOT_PERMITTED", LIC_004);
        m.put("LICENSE_ENTITLEMENT_MISSING", LIC_004);
        // Task 0058: intake for an entity that is suspended or not activated.
        m.put("ENTITY_NOT_ACTIVE", AUT_005);
        // Sign-in admission reasons: LexaVault's own (#229 to #250, #273).
        m.put("NO_MAPPED_ROLE", AUT_002);
        m.put("CONNECTION_LOOKUP_NOT_CONFIGURED", AUT_007);
        m.put("NO_ACTIVE_CONNECTION", AUT_007);
        m.put("NO_TRUSTED_TENANT_BINDING", AUT_007);
        m.put("INVALID_IDENTITY_CLAIMS", AUT_007);
        m.put("MISSING_ISSUER_OR_SUBJECT", AUT_007);
        m.put("OIDC_IDENTITY_REQUIRED", AUT_007);
        m.put("MEMBERSHIP_CONTEXT_UNAVAILABLE", AUT_007);
        m.put("MACHINE_IDENTITY_CONFIGURATION_INCOMPLETE", AUT_007);
        m.put("SHARED_ISSUER_REQUIRES_PERSISTED_CONNECTIONS", AUT_007);
        m.put("TENANT_DISCOVERY_NOT_ENABLED", AUT_007);
        m.put("TENANT_DISCOVERY_REQUIRES_OIDC", AUT_007);
        m.put("INVALID_LOCAL_IDENTITY_HEADER", AUT_007);
        m.put("INVALID_LOCAL_ROLE_HEADER", AUT_007);
        // An admitted entity left out of the choices because it is not active or missing (#236).
        m.put("TENANT_NOT_ACTIVE", AUT_005);
        m.put("TENANT_MISSING", AUT_005);
        // Sign-in admission reasons: the Enterprise Identity library's IdentityAdmissionException.Reason (#265 to #272).
        m.put("NOT_ENABLED", AUT_002);
        m.put("NO_VERIFIED_MEMBERSHIP", AUT_002);
        m.put("MEMBERSHIP_NOT_BACKED", AUT_002);
        m.put("NO_ROLE_FOR_ENTITY", AUT_002);
        m.put("UNKNOWN_ROLE", AUT_002);
        m.put("INVALID_ROLE_GRANT", AUT_002);
        m.put("PLATFORM_ROLE_IN_ENTITY_GRANT", AUT_002);
        // Task 0054 (platform-core 0.7.0): a platform-wide grant that names an entity role.
        m.put("ENTITY_ROLE_IN_PLATFORM_GRANT", AUT_002);
        m.put("GROUPS_NOT_A_COLLECTION", AUT_002);
        m.put("NO_ADMISSION_POLICY", AUT_002);
        m.put("AMBIGUOUS_SELECTION", AUT_004);
        m.put("NOT_AUTHORIZED_FOR_ENTITY", AUT_003);
        m.put("INVALID_SELECTION", AUT_003);
        m.put("CONTEXT_UNAVAILABLE", AUT_007);
        m.put("POLICY_UNAVAILABLE", AUT_007);
        m.put("MACHINE_BINDING_NOT_UNIQUE", AUT_008);
        // Task 0054 (SEC-7): a person's token on a machine path.
        m.put("MACHINE_IDENTITY_IS_A_PERSON", AUT_008);
        // Processing recovery (#347).
        m.put("PROCESSING_RECOVERY_EXHAUSTED", PRC_008);
        // Routing and derived delivery (#183 to #192, #196, #221, #227).
        m.put("ROUTING_MARKER_AMBIGUOUS", DLV_012);
        m.put("ROUTING_DESTINATION_UNAVAILABLE", DLV_006);
        m.put("ROUTING_DETECTION_FAILED", DLV_013);
        m.put("ROUTING_MASTER_UNAVAILABLE", DLV_011);
        m.put("ROUTING_FAILED", DLV_013);
        m.put("DERIVED_ROUTING_POST_EXPORT_FAILED", DLV_013);
        m.put("DERIVED_DOCUMENT_BUILD_FAILED", DLV_013);
        m.put("DERIVED_DESTINATION_DISABLED", DLV_006);
        m.put("DERIVED_DESTINATION_NOT_FOUND", DLV_006);
        m.put("DERIVED_DESTINATION_WRONG_PURPOSE", DLV_006);
        m.put("DELIVERY_CONFIGURATION", DLV_006);
        m.put("DERIVED_ARTIFACT_UNAVAILABLE", DLV_011);
        m.put("DERIVED_SOURCE_NOT_FOUND", DLV_011);
        m.put("DERIVED_DELIVERY_REFUSED", DLV_014);
        m.put("DELIVERY_NOT_RETRYABLE", DLV_014);
        m.put("DERIVED_DELIVERY_RUNTIME", SYS_001);
        m.put("DELIVERY_RUNTIME_ERROR", SYS_001);
        // Connection tests (#179, #304, #305, #307, #319).
        m.put("ADDRESS_NOT_ALLOWED", CFG_003);
        m.put("CONNECTION_INVALID", CFG_003);
        m.put("ADDRESS_NOT_RESOLVED", CFG_004);
        m.put("CONNECTION_TEST_FAILED", CFG_004);
        m.put("CONNECTION_UNAVAILABLE", CFG_004);
        m.put("DOCUMENT_STORE_TEST_UNAVAILABLE", CFG_004);
        m.put("DOCUMENT_STORE_CONNECTION_TEST_FAILED", CFG_004);
        m.put("DERIVED_CONNECTION_TEST_FAILED", CFG_004);
        return m;
    }
}
