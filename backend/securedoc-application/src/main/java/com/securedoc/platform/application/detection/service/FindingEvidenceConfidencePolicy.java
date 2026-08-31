package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationOutcome;

/**
 * Converts model-provided confidence into a bounded application confidence using controlled
 * deterministic evidence. The LLM's self-reported number is treated as one signal, not as a
 * calibrated probability.
 */
final class FindingEvidenceConfidencePolicy {

    private static final double MODEL_CONFIDENCE_CAP = 0.90;
    private static final double SUPPORTED_BOOST = 0.05;
    private static final double CONTRADICTION_CAP = 0.50;
    private static final double SEMANTIC_ONLY_CAP = 0.85;

    double effectiveSemanticConfidence(double modelConfidence, ValidationOutcome validationOutcome) {
        double boundedModel = Math.max(0.0, Math.min(MODEL_CONFIDENCE_CAP, modelConfidence));
        return switch (validationOutcome) {
            case SUPPORTS -> Math.min(0.95, boundedModel + SUPPORTED_BOOST);
            case CONTRADICTS -> Math.min(CONTRADICTION_CAP, boundedModel);
            case NOT_APPLICABLE -> Math.min(SEMANTIC_ONLY_CAP, boundedModel);
        };
    }
}
