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
        if (dependency == TechnicalDependency.STORAGE || code.name().startsWith("STORAGE_")) return ProcessingStage.STORAGE;
        return preferred == null ? ProcessingStage.UNKNOWN : preferred;
    }

    static String category(String code, String dependency) {
        if (code == null) return "TECHNICAL_PROCESSING_FAILURE";
        if (code.contains("TIMEOUT")) return "DEPENDENCY_TIMEOUT";
        if (code.contains("UNAVAILABLE")) return "DEPENDENCY_UNAVAILABLE";
        if (code.contains("RATE_LIMITED")) return "DEPENDENCY_RATE_LIMITED";
        if (code.contains("INVALID_RESPONSE") || code.contains("REJECTED")) return "DEPENDENCY_RESPONSE_ERROR";
        if (code.contains("DESTINATION_INVALID")) return "DELIVERY_CONFIGURATION";
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
            case "STORAGE_IO" -> "Internal document storage was temporarily unavailable.";
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
