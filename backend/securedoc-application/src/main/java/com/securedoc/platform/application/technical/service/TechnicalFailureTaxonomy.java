package com.securedoc.platform.application.technical.service;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.processing.ProcessingStage;

/** Shared stable taxonomy used by incident persistence and support-facing technical details. */
final class TechnicalFailureTaxonomy {
    private TechnicalFailureTaxonomy() {}

    static ProcessingStage stage(TechnicalDependency dependency, TechnicalFailureCode code, ProcessingStage preferred) {
        if (dependency == TechnicalDependency.AI || code.name().startsWith("AI_")) return ProcessingStage.AI_ANALYSIS;
        if (dependency == TechnicalDependency.OCR || code.name().startsWith("OCR_")) return ProcessingStage.OCR;
        if (dependency == TechnicalDependency.DELIVERY || code.name().startsWith("DELIVERY_")) return ProcessingStage.DELIVERY;
        if (dependency == TechnicalDependency.STORAGE || dependency == TechnicalDependency.KEY_SERVICE
                || dependency == TechnicalDependency.ERASE_LOG || code.name().startsWith("STORAGE_")) return ProcessingStage.STORAGE;
        if (code == TechnicalFailureCode.REDACTION_LOCATION_MISSING
                || code == TechnicalFailureCode.REDACTION_TARGETS_NOT_APPLIED) return ProcessingStage.REDACTION;
        if (code.name().startsWith("REDACTION_")) return ProcessingStage.VERIFICATION;
        if (dependency == TechnicalDependency.PROCESSING) return ProcessingStage.DOCUMENT_PROCESSING;
        return preferred == null ? ProcessingStage.UNKNOWN : preferred;
    }

    static String category(String code, String dependency) {
        if (code == null) return "TECHNICAL_PROCESSING_FAILURE";
        // Task 0023: checked before the substring rules ("REDACTION" contains "IO").
        if (code.equals("PROCESSING_LICENSE_DENIED")) return "LICENSE";
        if (code.equals("PROCESSING_DISPATCH_EXHAUSTED")) return "DISPATCH_EXHAUSTED";
        if (code.startsWith("REDACTION_")) return "REDACTION_SAFETY";
        // Task 0060.
        if (code.startsWith("KEY_SERVICE_")) return "KEY_SERVICE";
        if (code.startsWith("ERASE_")) return "ERASE_LOG";
        if (code.equals("STORED_FILE_REFUSED")) return "STORED_FILE";
        if (code.contains("TIMEOUT")) return "DEPENDENCY_TIMEOUT";
        if (code.contains("UNAVAILABLE")) return "DEPENDENCY_UNAVAILABLE";
        if (code.contains("RATE_LIMITED")) return "DEPENDENCY_RATE_LIMITED";
        if (code.contains("INVALID_RESPONSE") || code.contains("REJECTED")) return "DEPENDENCY_RESPONSE_ERROR";
        if (code.contains("DESTINATION_INVALID")) return "DELIVERY_CONFIGURATION";
        if (code.contains("SENSITIVITY_REFUSED")) return "DELIVERY_CONFIGURATION";
        if (code.contains("IO") || code.contains("PROCESS_FAILED")) {
            String safeDependency = dependency == null || dependency.isBlank() ? "TECHNICAL" : dependency;
            return safeDependency + "_FAILURE";
        }
        return "TECHNICAL_PROCESSING_FAILURE";
    }

    static String explanation(String code, boolean classifiedRetryable, boolean originalAvailable, boolean packageFailed) {
        String base = switch (code == null ? "" : code) {
            case "AI_TIMEOUT" -> "The configured AI provider did not respond within the allowed time.";
            case "AI_RATE_LIMITED" -> "The configured AI provider temporarily limited requests.";
            case "AI_UNAVAILABLE" -> "The configured AI provider was unavailable during processing.";
            case "AI_REJECTED", "AI_INVALID_RESPONSE" -> "The configured AI provider returned a response that could not be safely used.";
            case "OCR_TIMEOUT" -> "OCR did not complete within the allowed time.";
            case "OCR_UNAVAILABLE" -> "The configured OCR service was unavailable.";
            case "OCR_PROCESS_FAILED" -> "OCR could not process the document successfully.";
            case "DELIVERY_IO" -> "The configured delivery destination could not be reached successfully.";
            case "DELIVERY_DESTINATION_INVALID" -> "The configured delivery destination is not valid for this package.";
            case "DELIVERY_SENSITIVITY_REFUSED" -> "Destination refused the sensitivity level (LV-DLV-003). LexaVault sends the level it was given and never changes it; the destination's set-up needs checking.";
            case "STORAGE_IO" -> "Internal document storage was temporarily unavailable.";
            case "KEY_SERVICE_UNAVAILABLE" -> "The key service could not be reached, so the stored document could not be opened or saved (LV-SYS-010).";
            case "KEY_SERVICE_REFUSED" -> "The key service refused LexaVault, or the customer's keys are missing or switched off (LV-SYS-011).";
            case "STORED_FILE_REFUSED" -> "A stored file was not opened: it has no record of how it is protected, or it was changed (LV-SYS-012).";
            case "ERASE_REPLAY_REQUIRED" -> "The database was restored from a backup; no work is taken until the erase replay has run (LV-SYS-013).";
            case "ERASE_LOG_UNAVAILABLE" -> "The erase log could not be used, so nothing was erased (LV-SYS-014).";
            case "PROCESSING_DISPATCH_EXHAUSTED" -> "Processing could not be started after 3 tries, so the case was stopped. Automatic retries have ended.";
            case "PROCESSING_LICENSE_DENIED" -> "The tenant licence does not allow document processing, so the case was stopped. Check the licence, then retry.";
            case "REDACTION_VALUE_IN_OUTPUT_TEXT" -> "The final check found a value that was redacted in the text of the output, so the document was stopped and not delivered (LV-RED-005). Nothing about the value is recorded. Check the redacted pages, or turn searchable redacted pages off for the entity, then retry.";
            case "REDACTION_VALUE_LEFT_ON_CHECKED_PAGE" -> "The final check still found a value on a page a person had finished, so the document was stopped and not delivered (LV-RED-002). Nothing about the value is recorded. The pages are named in the audit trail (final check).";
            case "REDACTION_LOCATION_MISSING" -> "A confirmed finding has no location on the page, so it could not be covered. Locate or dismiss the finding.";
            case "REDACTION_SOURCE_PAGES_UNKNOWN", "REDACTION_PAGE_COUNT_MISMATCH", "REDACTION_TARGETS_NOT_APPLIED",
                 "REDACTION_OUTPUT_EMPTY", "REDACTION_TEXT_LAYER_REMAINS", "REDACTION_ANNOTATIONS_REMAIN",
                 "REDACTION_HIDDEN_CONTENT_REMAINS",
                 "REDACTION_REGION_NOT_COVERED", "REDACTION_OUTPUT_UNPARSABLE" ->
                    "The redacted output failed its safety check, so it was not delivered. The code says which check failed.";
            default -> "Processing stopped because of a technical failure. Use the incident or correlation ID for support diagnostics.";
        };
        if (!packageFailed) {
            return base + " Business processing may continue independently; this technical incident remains recorded for investigation.";
        }
        if (classifiedRetryable && !originalAvailable) {
            return base + " Retry is unavailable because the retained original is no longer available.";
        }
        if (classifiedRetryable) {
            return base + " The failure is classified as safe to retry.";
        }
        return base;
    }
}
