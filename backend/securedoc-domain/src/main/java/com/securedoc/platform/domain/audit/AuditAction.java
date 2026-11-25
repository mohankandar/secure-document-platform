package com.securedoc.platform.domain.audit;

/**
 * Enumeration of auditable actions in the system.
 */
public enum AuditAction {

    // Package lifecycle
    PACKAGE_CREATED,
    PACKAGE_SUBMITTED,
    PACKAGE_PROCESSING_STARTED,
    PACKAGE_PROCESSING_RECOVERED,
    PACKAGE_PROCESSING_RECOVERY_EXHAUSTED,
    PACKAGE_PROCESSING_COMPLETED,
    PACKAGE_APPROVED,
    PACKAGE_REJECTED,
    PACKAGE_VAULTED,
    PACKAGE_CANCELLED,
    PACKAGE_FINAL_APPROVAL_REQUIRED,
    PACKAGE_FINAL_APPROVED,
    PACKAGE_REPROCESS_REQUESTED,
    PACKAGE_TECHNICAL_RETRY_REQUESTED,
    PACKAGE_HIDDEN_FROM_DASHBOARD,
    PACKAGE_EXPORT_RECORDED,
    RETENTION_SCHEDULED,
    RETENTION_HOLD_EXTENDED,
    RETENTION_HOLD_RELEASED,
    TECHNICAL_ATTENTION_AUTO_CLOSED,
    SUPPORT_INCIDENT_ACTION_RECORDED,
    REDACTED_ARTIFACT_PURGED,
    ORIGINAL_ARTIFACT_PURGED,
    PACKAGE_PURGED,
    LIFECYCLE_POLICY_CHANGED,

    // Business identity / intake
    BUSINESS_RECORD_CREATED,
    BUSINESS_RECORD_LINKED,
    BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED,
    SENSITIVE_DATA_POLICY_CHANGED,
    DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED,
    DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED,
    DOCUMENT_INTELLIGENCE_ROLLED_BACK,
    DASHBOARD_CONFIGURATION_CHANGED,
    INTAKE_WAITING_FOR_TRIGGER,
    INTAKE_ACCEPTED,
    INTAKE_REJECTED,
    INTAKE_DUPLICATE,
    /** Task 0021: a source sent an unknown sensitivity level; taken as Standard. Detail holds the code LV-INT-005 only. */
    INTAKE_SENSITIVITY_LEVEL_UNKNOWN,
    /** Task 0021: Tenant IT changed a watched folder's settings (its sensitivity level). Detail: level codes only. */
    WATCHED_FOLDER_SETTINGS_CHANGED,
    EXTERNAL_INTAKE_CREATED,
    EXTERNAL_DOCUMENT_RECEIVED,
    EXTERNAL_INTAKE_ACCEPTED,
    EXTERNAL_INTAKE_EXPIRED,
    EXTERNAL_INTAKE_CLEANED,

    // Document operations
    DOCUMENT_UPLOADED,
    DOCUMENT_EXTRACTION_STARTED,
    DOCUMENT_EXTRACTION_COMPLETED,
    DOCUMENT_DETECTION_COMPLETED,
    DOCUMENT_REDACTION_COMPLETED,
    DOCUMENT_REDACTION_VERIFIED,
    /** Task 0050: a digitally signed document lost its signature by redaction (document id only, no signer). */
    DOCUMENT_SIGNATURE_REMOVED,
    /** Task 0083: a second AI read the finished file. Pages, kinds and counts only. */
    DOCUMENT_SECOND_CHECK,
    DOCUMENT_EXPORTED,
    DOCUMENT_EXPORT_FAILED,
    DOCUMENT_PROCESSING_FAILED,

    // Findings
    FINDING_DETECTED,
    FINDING_CONFIRMED,
    FINDING_DISMISSED,
    FINDING_REDACTED,
    FINDING_OVERRIDDEN,
    FINDING_BOUNDING_BOX_UPDATED,

    // Review
    REVIEW_TASK_CREATED,
    REVIEW_TASK_ASSIGNED,
    REVIEW_STARTED,
    REVIEW_APPROVED,
    REVIEW_REJECTED,
    REVIEW_ESCALATED,
    REVIEW_PAGE_PREVIEWED,
    /** Task 0074: a person checked an unreadable page by hand (page number and number of boxes only). */
    REVIEW_PAGE_CHECKED,
    BUSINESS_MANUAL_FALLBACK_STARTED,
    BUSINESS_MANUAL_FALLBACK_REGION_ADDED,
    BUSINESS_MANUAL_FALLBACK_REGION_REMOVED,
    BUSINESS_MANUAL_FALLBACK_SUBMITTED,

    // Vault and access
    VAULT_ENTRY_REGISTERED,
    VAULT_ENTRY_SEALED,
    ACCESS_REQUESTED,
    ACCESS_APPROVED,
    ACCESS_DENIED,
    ACCESS_REVOKED,
    DOCUMENT_DOWNLOADED,

    // Tenant administration
    TENANT_CREATED,
    TENANT_ACTIVATED,
    TENANT_SUSPENDED,
    TENANT_DEACTIVATED,
    // Task 0058: entity slots, an entity's time zone and the entity a person works in. Codes only.
    ENTITY_ACTIVATED,
    ENTITY_RENAMED,
    ENTITY_SUSPENDED,
    ENTITY_RESUMED,
    ENTITY_TIME_ZONE_CHANGED,
    ENTITY_SWITCHED,
    CAPABILITY_CHANGED,

    // Policy management
    POLICY_CREATED,
    POLICY_VERSION_PUBLISHED,
    POLICY_DEACTIVATED,
    POLICY_EVALUATED,

    // Security
    AUTHORIZATION_FAILED,
    SECURITY_CONTEXT_INVALID,

    // Reporting
    REPORT_CSV_EXPORTED,
    AUDIT_EXPORTED,
    REPORTING_CONFIGURATION_CHANGED,
    /**
     * A compliance report PDF was created. Written by build plan step 3 (compliance report backend); nothing writes
     * it yet. Its detail will hold the period, the edition, and {@code fingerprint=} followed by the SHA-256 hex of
     * the PDF. Never a value found in a document.
     */
    COMPLIANCE_REPORT_CREATED,

    // IT / Platform administration
    PLATFORM_HEALTH_CHECKED,
    /** Task 0067: IT's own maximums of workers and AI requests at once; names and numbers only. */
    PROCESSING_MAXIMUMS_CHANGED,
    /** Task 0073: names of priorities only ("priority=NORMAL->URGENT"). */
    CASE_PRIORITY_CHANGED,
    /** Task 0065: the number of characters only, never the text. */
    CASE_INSTRUCTIONS_CHANGED,
    SOURCE_PRIORITY_CHANGED,
    /** Task 0079: the window's times only. */
    SOURCE_BULK_CHANGED,
    WATCHED_FOLDER_SCAN_TRIGGERED,
    INTEGRATION_CONNECTION_CREATED,
    INTEGRATION_CONNECTION_UPDATED,
    INTEGRATION_CONNECTION_ENABLED,
    INTEGRATION_CONNECTION_DISABLED,
    POLLING_RUN_STARTED,
    POLLING_RUN_COMPLETED,
    POLLING_RUN_FAILED,
    POLLING_SOURCE_ACKNOWLEDGED,
    /** Task 0023 B1: one pickup package of a polling run was not taken in; detail holds the package position, code and exception type. */
    POLLING_PACKAGE_FAILED,
    INTEGRATION_CONNECTION_TESTED,
    ENTERPRISE_IDENTITY_DRAFT_SAVED,
    ENTERPRISE_IDENTITY_CONNECTION_TESTED,
    ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED,
    ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED,
    ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK,
    ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED,
    PROCESSING_STORAGE_PROVIDER_CREATED,
    PROCESSING_STORAGE_PROVIDER_TESTED,
    PROCESSING_STORAGE_PROVIDER_ACTIVATED,
    PROCESSING_STORAGE_PROVIDER_RETIRED,
    PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED,
    LICENSE_INSTALLED,
    LICENSE_VALIDATION_FAILED,
    LICENSE_ENFORCEMENT_DENIED,
    /** Task 0057. */
    DIAGNOSTIC_BUNDLE_DOWNLOADED,
    // Task 0059: notifications and approval links. Codes, ids and field names only.
    NOTIFICATION_SETTINGS_CHANGED,
    NOTIFICATION_EVENT_CHANGED,
    NOTIFICATION_TEST_SENT,
    DELIVERY_APPROVAL_LINK_USED,
    DELIVERY_APPROVAL_LINK_REFUSED,
    BRANDING_CONFIGURATION_CHANGED,

    // Derived-document routing
    ROUTING_RULE_CREATED,
    ROUTING_RULE_UPDATED,
    ROUTING_RULE_DELETED,
    ROUTING_ATTENTION_REQUIRED,
    DERIVED_DOCUMENT_CREATED,
    DERIVED_DOCUMENT_DELIVERED,
    DERIVED_DOCUMENT_DELIVERY_FAILED,

    // Export jobs (task 0027, staged pipeline P1). Codes and ids only.
    EXPORT_JOB_QUEUED,
    EXPORT_JOB_STATUS_CHANGED,
    EXPORT_JOB_RESEND_REQUESTED,
    EXPORT_JOB_IT_RETRY_REQUESTED,
    /** A job reached the IT error queue. The notifications module (later) picks these up. */
    EXPORT_JOB_NEEDS_IT_NOTIFICATION,
    EXPORT_DESTINATION_PAUSED,
    EXPORT_DESTINATION_RESUMED,
    EXPORT_DESTINATION_NEEDS_IT,

    // Export follow-ups (task 0028). Codes, ids and numbers only.
    /** Resend all for one destination: its WAITING_FOR_YOU jobs back to READY, one attempt each. */
    EXPORT_JOB_RESEND_ALL_REQUESTED,
    /** The export was rebuilt once from the stored redacted copies, before the 3rd retry. */
    EXPORT_JOB_REBUILT,
    /** IT retried a group (one destination, one code) of NEEDS_IT jobs. */
    EXPORT_JOB_IT_GROUP_RETRY_REQUESTED,
    /** A destination answered 429: it waits for Retry-After and gets one worker less. */
    EXPORT_DESTINATION_RATE_LIMITED,
    /** "LexaVault can't reach its destinations": every destination failed its check within the same minutes. */
    EXPORT_DESTINATIONS_UNREACHABLE_ALERT,
    EXPORT_DESTINATIONS_UNREACHABLE_ALERT_CLEARED,
    /** The Business Admin set or cleared a retention period per export destination. */
    EXPORT_RETENTION_PERIOD_CHANGED,
    /** A split destination's copy was purged after its own retention period from receipt. */
    SPLIT_COPY_PURGED,
    /** Task 0034: finished export jobs and cleared reachability alerts past their keep time were removed (counts only). */
    PIPELINE_JOBS_CLEANED,

    // Staged pipeline P2 (task 0048): Text, Find and Redact jobs per document. Codes and ids only.
    PIPELINE_JOB_QUEUED,
    PIPELINE_JOB_STATUS_CHANGED,

    /** Task 0049: stored text and redacted files that no record points to were removed (counts only). */
    UNRECORDED_FILES_CLEANED,

    /** Task 0060: stored files were erased, written to the erase log first. Ids, kind, rule and entry number only. */
    /** Task 0061: the case field values of a business record erased, through the erase log. */
    CASE_FIELDS_ERASED,
    FILES_ERASED
}
