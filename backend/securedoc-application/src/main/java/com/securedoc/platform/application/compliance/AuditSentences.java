package com.securedoc.platform.application.compliance;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * The Admin › Audit trail sentence words, ported once from the frontend's {@code studio/admin/auditRules.ts}
 * ({@code SENTENCES}, {@code FINDING_SENTENCES}, {@code whatHappened}, {@code resultWord}, {@code personName}), so
 * the appendix says what the screen says. A finding's sentence names its sensitive TYPE only, never a value.
 */
public final class AuditSentences {

    /** The id LexaVault records for its own automatic work. */
    public static final String SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-000000000000";
    public static final String SYSTEM_ACTOR_NAME = "LexaVault (automatic)";

    private static final Pattern TYPE = Pattern.compile("\\btype=([A-Za-z_]+)");

    private static final Map<String, String> SENTENCES = Map.ofEntries(
            // Cases
            entry("PACKAGE_CREATED", "Created a case"),
            entry("PACKAGE_SUBMITTED", "Sent a case for processing"),
            entry("PACKAGE_PROCESSING_STARTED", "Started processing"),
            entry("PACKAGE_PROCESSING_RECOVERED", "Picked up interrupted processing"),
            entry("PACKAGE_PROCESSING_RECOVERY_EXHAUSTED", "Gave up on interrupted processing"),
            entry("PACKAGE_PROCESSING_COMPLETED", "Finished processing"),
            entry("PACKAGE_APPROVED", "Approved the case"),
            entry("PACKAGE_REJECTED", "Rejected the case"),
            entry("PACKAGE_VAULTED", "Delivered to the document store"),
            entry("PACKAGE_CANCELLED", "Cancelled the case"),
            entry("PACKAGE_FINAL_APPROVAL_REQUIRED", "Asked for delivery approval"),
            entry("PACKAGE_FINAL_APPROVED", "Approved delivery"),
            entry("PACKAGE_REPROCESS_REQUESTED", "Asked to process the case again"),
            entry("PACKAGE_TECHNICAL_RETRY_REQUESTED", "Retried after a technical problem"),
            entry("PACKAGE_HIDDEN_FROM_DASHBOARD", "Hid the case from Cases"),
            entry("PACKAGE_EXPORT_RECORDED", "Recorded an export"),
            entry("PACKAGE_PURGED", "Deleted the case after its retention period"),
            entry("REDACTED_ARTIFACT_PURGED", "Deleted the redacted copy after its retention period"),
            entry("ORIGINAL_ARTIFACT_PURGED", "Deleted the original after its retention period"),
            entry("FILES_ERASED", "Erased stored files (recorded in the erase log)"),
            entry("CASE_FIELDS_ERASED", "Erased case field values"),
            entry("RETENTION_SCHEDULED", "Scheduled deletion"),
            entry("RETENTION_HOLD_EXTENDED", "Extended a hold"),
            entry("RETENTION_HOLD_RELEASED", "Released a hold"),
            entry("TECHNICAL_ATTENTION_AUTO_CLOSED", "Closed a solved technical problem"),
            entry("SUPPORT_INCIDENT_ACTION_RECORDED", "Recorded a support action"),
            entry("DOCUMENT_UPLOADED", "Added a document"),
            entry("DOCUMENT_EXTRACTION_STARTED", "Started reading a document"),
            entry("DOCUMENT_EXTRACTION_COMPLETED", "Read a document"),
            entry("DOCUMENT_DETECTION_COMPLETED", "Checked a document for sensitive data"),
            entry("DOCUMENT_REDACTION_COMPLETED", "Redacted a document"),
            entry("DOCUMENT_REDACTION_VERIFIED", "Verified a redacted document"),
            entry("DOCUMENT_SECOND_CHECK", "Checked the finished file a second time"),
            entry("DOCUMENT_SIGNATURE_REMOVED", "Removed a digital signature by redaction"),
            entry("DOCUMENT_PROCESSING_FAILED", "Could not process a document"),
            entry("DOCUMENT_DOWNLOADED", "Downloaded a document"),
            entry("DOCUMENT_EXPORTED", "Exported a document"),
            entry("DOCUMENT_EXPORT_FAILED", "Could not export a document"),
            entry("REVIEW_TASK_CREATED", "Sent the case to review"),
            entry("REVIEW_TASK_ASSIGNED", "Assigned a review"),
            entry("REVIEW_STARTED", "Started a review"),
            entry("REVIEW_APPROVED", "Finished the review"),
            entry("REVIEW_REJECTED", "Rejected in review"),
            entry("REVIEW_ESCALATED", "Escalated a review"),
            entry("REVIEW_PAGE_PREVIEWED", "Viewed a page in review"),
            entry("REVIEW_PAGE_CHECKED", "Checked an unreadable page by hand"),
            entry("BUSINESS_MANUAL_FALLBACK_STARTED", "Started redacting by hand"),
            entry("BUSINESS_MANUAL_FALLBACK_REGION_ADDED", "Marked an area to redact"),
            entry("BUSINESS_MANUAL_FALLBACK_REGION_REMOVED", "Removed a marked area"),
            entry("BUSINESS_MANUAL_FALLBACK_SUBMITTED", "Finished redacting by hand"),
            entry("BUSINESS_RECORD_CREATED", "Created a business record"),
            entry("BUSINESS_RECORD_LINKED", "Linked a business record"),
            entry("INTAKE_WAITING_FOR_TRIGGER", "Waiting for the READY file"),
            entry("INTAKE_ACCEPTED", "Took in a case from the watched folder"),
            entry("INTAKE_REJECTED", "Turned away a case from the watched folder"),
            entry("INTAKE_DUPLICATE", "Skipped a case already taken in"),
            entry("EXTERNAL_INTAKE_CREATED", "Opened a link for receiving documents"),
            entry("EXTERNAL_DOCUMENT_RECEIVED", "Received a document"),
            entry("EXTERNAL_INTAKE_ACCEPTED", "Took in received documents"),
            entry("EXTERNAL_INTAKE_EXPIRED", "A link for receiving documents expired"),
            entry("EXTERNAL_INTAKE_CLEANED", "Cleaned up an expired link"),
            entry("VAULT_ENTRY_REGISTERED", "Registered with the document store"),
            entry("VAULT_ENTRY_SEALED", "Sealed in the document store"),
            entry("ACCESS_REQUESTED", "Asked for access"),
            entry("ACCESS_APPROVED", "Approved access"),
            entry("ACCESS_DENIED", "Refused access"),
            entry("ACCESS_REVOKED", "Removed access"),
            entry("DERIVED_DOCUMENT_CREATED", "Made a document for delivery"),
            entry("DERIVED_DOCUMENT_DELIVERED", "Delivered a document"),
            entry("DERIVED_DOCUMENT_DELIVERY_FAILED", "Could not deliver a document"),
            entry("ROUTING_ATTENTION_REQUIRED", "A delivery needs attention"),
            // Settings
            entry("BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED", "Changed the case identifiers"),
            entry("SENSITIVE_DATA_POLICY_CHANGED", "Changed the sensitive-data settings"),
            entry("DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED", "Changed the AI settings"),
            entry("DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED", "Saved the AI settings as known good"),
            entry("DOCUMENT_INTELLIGENCE_ROLLED_BACK", "Went back to the known-good AI settings"),
            entry("DASHBOARD_CONFIGURATION_CHANGED", "Changed the Cases columns"),
            entry("LIFECYCLE_POLICY_CHANGED", "Changed how long cases are kept"),
            entry("POLICY_CREATED", "Created a policy"),
            entry("POLICY_VERSION_PUBLISHED", "Published a policy"),
            entry("POLICY_DEACTIVATED", "Switched off a policy"),
            entry("POLICY_EVALUATED", "Checked a policy"),
            entry("REPORTING_CONFIGURATION_CHANGED", "Changed the report settings"),
            entry("TENANT_CREATED", "Created a customer"),
            entry("TENANT_ACTIVATED", "Switched on a customer"),
            entry("TENANT_SUSPENDED", "Paused a customer"),
            entry("TENANT_DEACTIVATED", "Switched off a customer"),
            entry("ENTITY_ACTIVATED", "Activated an entity"),
            entry("ENTITY_RENAMED", "Renamed an entity"),
            entry("ENTITY_SUSPENDED", "Suspended an entity"),
            entry("ENTITY_RESUMED", "Resumed an entity"),
            entry("ENTITY_TIME_ZONE_CHANGED", "Changed an entity’s time zone"),
            entry("ENTITY_SWITCHED", "Switched to this entity"),
            entry("CAPABILITY_CHANGED", "Changed what a role can do"),
            entry("INTEGRATION_CONNECTION_CREATED", "Added a connection"),
            entry("INTEGRATION_CONNECTION_UPDATED", "Changed a connection"),
            entry("INTEGRATION_CONNECTION_ENABLED", "Switched on a connection"),
            entry("INTEGRATION_CONNECTION_DISABLED", "Switched off a connection"),
            entry("INTEGRATION_CONNECTION_TESTED", "Tested a connection"),
            entry("POLLING_RUN_STARTED", "Started checking a connection for documents"),
            entry("POLLING_RUN_COMPLETED", "Checked a connection for documents"),
            entry("POLLING_RUN_FAILED", "Could not check a connection for documents"),
            entry("POLLING_SOURCE_ACKNOWLEDGED", "Confirmed receipt to a connection"),
            entry("WATCHED_FOLDER_SCAN_TRIGGERED", "Checked the watched folder"),
            entry("PLATFORM_HEALTH_CHECKED", "Ran health checks"),
            entry("PROCESSING_MAXIMUMS_CHANGED", "Changed the processing maximums"),
            entry("CASE_PRIORITY_CHANGED", "Changed the case’s priority"),
            entry("CASE_INSTRUCTIONS_CHANGED", "Changed the case’s instructions for the AI"),
            entry("SOURCE_PRIORITY_CHANGED", "Changed a source’s default priority"),
            entry("SOURCE_BULK_CHANGED", "Changed a source’s migration setting"),
            entry("PROCESSING_STORAGE_PROVIDER_CREATED", "Added processing storage"),
            entry("PROCESSING_STORAGE_PROVIDER_TESTED", "Tested processing storage"),
            entry("PROCESSING_STORAGE_PROVIDER_ACTIVATED", "Made processing storage the default"),
            entry("PROCESSING_STORAGE_PROVIDER_RETIRED", "Stopped using processing storage"),
            entry("PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED", "Changed a customer’s processing storage"),
            entry("LICENSE_INSTALLED", "Installed a licence"),
            entry("BRANDING_CONFIGURATION_CHANGED", "Changed the branding"),
            entry("ROUTING_RULE_CREATED", "Added a delivery rule"),
            entry("ROUTING_RULE_UPDATED", "Changed a delivery rule"),
            entry("ROUTING_RULE_DELETED", "Removed a delivery rule"),
            // Sign-in and security
            entry("ENTERPRISE_IDENTITY_DRAFT_SAVED", "Saved a sign-in draft"),
            entry("ENTERPRISE_IDENTITY_CONNECTION_TESTED", "Tested the sign-in setup"),
            entry("ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED", "Switched on the new sign-in setup"),
            entry("ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED", "Could not switch on the new sign-in setup"),
            entry("ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK", "Went back to the previous sign-in setup"),
            entry("ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED", "Could not go back to the previous sign-in setup"),
            entry("AUTHORIZATION_FAILED", "Tried something their role does not allow"),
            entry("SECURITY_CONTEXT_INVALID", "Sent a request that could not be trusted"),
            entry("LICENSE_VALIDATION_FAILED", "The licence could not be checked"),
            entry("LICENSE_ENFORCEMENT_DENIED", "Refused by the licence"),
            entry("DIAGNOSTIC_BUNDLE_DOWNLOADED", "Downloaded a diagnostic bundle"),
            entry("NOTIFICATION_SETTINGS_CHANGED", "Changed the e-mail settings for notifications"),
            entry("NOTIFICATION_EVENT_CHANGED", "Changed when a notification is sent"),
            entry("NOTIFICATION_TEST_SENT", "Sent a test notification"),
            entry("DELIVERY_APPROVAL_LINK_USED", "Used an approval link"),
            entry("DELIVERY_APPROVAL_LINK_REFUSED", "An approval link was refused"),
            // Exports
            entry("REPORT_CSV_EXPORTED", "Exported a report"),
            entry("AUDIT_EXPORTED", "Exported the audit trail"));

    private static final Map<String, String> FINDING_SENTENCES = Map.of(
            "FINDING_DETECTED", "Found",
            "FINDING_CONFIRMED", "Redacted",
            "FINDING_REDACTED", "Redacted",
            "FINDING_DISMISSED", "Kept",
            "FINDING_OVERRIDDEN", "Changed the decision on",
            "FINDING_BOUNDING_BOX_UPDATED", "Moved the box around");

    private AuditSentences() {
    }

    /** What happened, as a sentence: "Approved delivery", "Redacted a Social Security Number". Never a raw code. */
    public static String whatHappened(String action, String detail) {
        String verb = FINDING_SENTENCES.get(action);
        if (verb != null) {
            String thing = typePhrase(detail);
            return "FINDING_DISMISSED".equals(action) ? verb + " " + thing + " visible" : verb + " " + thing;
        }
        String sentence = SENTENCES.get(action);
        return sentence != null ? sentence : SensitiveTypeNames.sentenceCase(action == null ? "" : action);
    }

    /** "Success", "Failed", "Refused". */
    public static String resultWord(String outcome) {
        if (outcome == null || outcome.isBlank()) return "Not recorded";
        return switch (outcome) {
            case "SUCCESS" -> "Success";
            case "FAILURE" -> "Failed";
            case "DENIED" -> "Refused";
            default -> SensitiveTypeNames.sentenceCase(outcome);
        };
    }

    /**
     * Who did it: the recorded display name, "LexaVault (automatic)" for the system, or "Person" and the last 8
     * characters of the actor id for records written before display names were recorded (task 0016).
     */
    public static String person(String actorId, String actorDisplayName) {
        if (SYSTEM_ACTOR_ID.equals(actorId)) return SYSTEM_ACTOR_NAME;
        if (actorDisplayName != null && !actorDisplayName.isBlank()) return actorDisplayName.strip();
        if (actorId == null || actorId.isBlank()) return "Unknown";
        String compact = actorId.replace("-", "");
        return "Person " + compact.substring(Math.max(0, compact.length() - 8));
    }

    /** "a Social Security Number", "a credit score", "a finding". */
    private static String typePhrase(String detail) {
        Matcher m = TYPE.matcher(detail == null ? "" : detail);
        if (!m.find()) return "a finding";
        String name = SensitiveTypeNames.name(m.group(1));
        String[] words = name.split(" ");
        boolean proper = words.length > 1;
        for (String w : words) {
            if (w.isEmpty() || !Character.isUpperCase(w.charAt(0))) proper = false;
        }
        String phrase = proper ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return ("aeiouAEIOU".indexOf(phrase.charAt(0)) >= 0 ? "an " : "a ") + phrase;
    }
}
