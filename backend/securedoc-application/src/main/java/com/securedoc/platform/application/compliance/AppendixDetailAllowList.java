package com.securedoc.platform.application.compliance;

import com.securedoc.platform.domain.audit.AuditAction;

import java.util.EnumSet;
import java.util.Set;

/**
 * The audit actions whose recorded detail may be printed in the compliance report's appendix (task 0017 B3).
 *
 * <p>An action is on the list only when every call site that writes it builds the detail from fixed text, counts,
 * settings, enum codes, ids, page numbers and sensitive-type names (checked 2026-11-19, listed in report 0017). Never a
 * file name, borrower name, loan number, business identifier value, found text, typed reason, connection or rule
 * name, client-supplied text or an exception's message. Every other action, including any action added later,
 * prints "—" until it is reviewed and added here.
 *
 * <p>Left off on purpose (their detail can hold such text): {@code DOCUMENT_EXPORTED} (file name),
 * {@code AUDIT_EXPORTED} (unchecked filter text), {@code POLLING_RUN_FAILED} (exception message),
 * {@code POLLING_SOURCE_ACKNOWLEDGED} (source revision), {@code INTEGRATION_CONNECTION_CREATED/UPDATED/ENABLED/
 * DISABLED} (connection name), {@code ROUTING_RULE_CREATED/UPDATED} and {@code DERIVED_DOCUMENT_CREATED/DELIVERED}
 * (destination label), {@code LICENSE_INSTALLED} (unchecked edition text), and every action nothing writes today.
 */
public final class AppendixDetailAllowList {

    public static final String NOT_PRINTED = "—";

    /** Even an allowed detail is cut to this length in the appendix. */
    static final int MAX_PRINTED = 300;

    private static final Set<AuditAction> ALLOWED = EnumSet.of(
            // Cases
            AuditAction.PACKAGE_CREATED, AuditAction.PACKAGE_SUBMITTED, AuditAction.PACKAGE_PROCESSING_STARTED,
            AuditAction.PACKAGE_PROCESSING_RECOVERED, AuditAction.PACKAGE_PROCESSING_RECOVERY_EXHAUSTED,
            AuditAction.PACKAGE_PROCESSING_COMPLETED, AuditAction.PACKAGE_APPROVED,
            AuditAction.PACKAGE_FINAL_APPROVAL_REQUIRED, AuditAction.PACKAGE_FINAL_APPROVED,
            AuditAction.PACKAGE_REPROCESS_REQUESTED, AuditAction.PACKAGE_TECHNICAL_RETRY_REQUESTED,
            AuditAction.PACKAGE_HIDDEN_FROM_DASHBOARD, AuditAction.PACKAGE_EXPORT_RECORDED,
            AuditAction.RETENTION_SCHEDULED, AuditAction.RETENTION_HOLD_EXTENDED, AuditAction.RETENTION_HOLD_RELEASED,
            AuditAction.TECHNICAL_ATTENTION_AUTO_CLOSED, AuditAction.SUPPORT_INCIDENT_ACTION_RECORDED,
            AuditAction.REDACTED_ARTIFACT_PURGED, AuditAction.ORIGINAL_ARTIFACT_PURGED, AuditAction.PACKAGE_PURGED,
            AuditAction.LIFECYCLE_POLICY_CHANGED,
            // Business identity and intake
            AuditAction.BUSINESS_RECORD_CREATED, AuditAction.BUSINESS_RECORD_LINKED,
            AuditAction.BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED, AuditAction.SENSITIVE_DATA_POLICY_CHANGED,
            AuditAction.DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED,
            AuditAction.DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED, AuditAction.DOCUMENT_INTELLIGENCE_ROLLED_BACK,
            AuditAction.DASHBOARD_CONFIGURATION_CHANGED, AuditAction.INTAKE_WAITING_FOR_TRIGGER,
            AuditAction.INTAKE_ACCEPTED, AuditAction.INTAKE_REJECTED, AuditAction.INTAKE_DUPLICATE,
            AuditAction.EXTERNAL_INTAKE_CREATED, AuditAction.EXTERNAL_DOCUMENT_RECEIVED,
            AuditAction.EXTERNAL_INTAKE_ACCEPTED, AuditAction.EXTERNAL_INTAKE_EXPIRED,
            AuditAction.EXTERNAL_INTAKE_CLEANED,
            // Task 0021: fixed code and scope only (LV-INT-005), never the value the source sent.
            AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN,
            // Documents and findings
            AuditAction.DOCUMENT_UPLOADED, AuditAction.DOCUMENT_EXTRACTION_STARTED,
            AuditAction.DOCUMENT_EXTRACTION_COMPLETED, AuditAction.DOCUMENT_DETECTION_COMPLETED,
            AuditAction.DOCUMENT_REDACTION_COMPLETED, AuditAction.DOCUMENT_REDACTION_VERIFIED,
            AuditAction.DOCUMENT_EXPORT_FAILED, AuditAction.DOCUMENT_PROCESSING_FAILED,
            AuditAction.FINDING_CONFIRMED, AuditAction.FINDING_DISMISSED, AuditAction.FINDING_BOUNDING_BOX_UPDATED,
            // Review
            AuditAction.REVIEW_TASK_CREATED, AuditAction.REVIEW_TASK_ASSIGNED, AuditAction.REVIEW_STARTED,
            AuditAction.REVIEW_APPROVED, AuditAction.REVIEW_PAGE_PREVIEWED,
            AuditAction.BUSINESS_MANUAL_FALLBACK_STARTED, AuditAction.BUSINESS_MANUAL_FALLBACK_REGION_ADDED,
            AuditAction.BUSINESS_MANUAL_FALLBACK_REGION_REMOVED, AuditAction.BUSINESS_MANUAL_FALLBACK_SUBMITTED,
            // Vault and access
            AuditAction.VAULT_ENTRY_REGISTERED, AuditAction.ACCESS_REQUESTED, AuditAction.ACCESS_APPROVED,
            AuditAction.ACCESS_DENIED, AuditAction.DOCUMENT_DOWNLOADED,
            // Task 0018 A2: written only by CapabilityAuthorizationInterceptor, from a capability code, the HTTP
            // method and the route pattern from the code (never the URL, query string or body).
            AuditAction.AUTHORIZATION_FAILED,
            // Reporting
            AuditAction.REPORT_CSV_EXPORTED, AuditAction.REPORTING_CONFIGURATION_CHANGED,
            AuditAction.COMPLIANCE_REPORT_CREATED,
            // IT and platform administration
            AuditAction.PLATFORM_HEALTH_CHECKED, AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED,
            // Task 0021: level codes only ("sensitivityLevel=STANDARD->CONFIDENTIAL").
            AuditAction.WATCHED_FOLDER_SETTINGS_CHANGED,
            AuditAction.POLLING_RUN_STARTED, AuditAction.POLLING_RUN_COMPLETED, AuditAction.INTEGRATION_CONNECTION_TESTED,
            AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED, AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,
            AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED,
            AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED,
            AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK,
            AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED,
            AuditAction.PROCESSING_STORAGE_PROVIDER_CREATED, AuditAction.PROCESSING_STORAGE_PROVIDER_TESTED,
            AuditAction.PROCESSING_STORAGE_PROVIDER_ACTIVATED, AuditAction.PROCESSING_STORAGE_PROVIDER_RETIRED,
            AuditAction.PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED, AuditAction.LICENSE_VALIDATION_FAILED,
            AuditAction.LICENSE_ENFORCEMENT_DENIED, AuditAction.BRANDING_CONFIGURATION_CHANGED,
            // Derived-document routing
            AuditAction.ROUTING_RULE_DELETED, AuditAction.ROUTING_ATTENTION_REQUIRED,
            AuditAction.DERIVED_DOCUMENT_DELIVERY_FAILED);

    private AppendixDetailAllowList() {
    }

    public static Set<AuditAction> allowed() {
        return Set.copyOf(ALLOWED);
    }

    public static boolean isAllowed(String action) {
        if (action == null) return false;
        try {
            return ALLOWED.contains(AuditAction.valueOf(action));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The detail as printed: the recorded detail for an allowed action, otherwise "—". */
    public static String printable(String action, String detail) {
        if (!isAllowed(action) || detail == null || detail.isBlank()) return NOT_PRINTED;
        String clean = detail.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > MAX_PRINTED ? clean.substring(0, MAX_PRINTED) + "…" : clean;
    }
}
