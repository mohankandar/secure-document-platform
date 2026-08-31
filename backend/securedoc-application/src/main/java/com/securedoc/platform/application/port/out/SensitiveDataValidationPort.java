package com.securedoc.platform.application.port.out;

import java.util.Objects;

/**
 * Deterministic validation evidence for a sensitive-content candidate that was already
 * discovered by AI/semantic analysis.
 *
 * <p>This port does not discover candidates and must never gate whether AI analysis runs.
 * It provides controlled supporting or contradicting evidence for structured categories.
 * Raw candidate text is transient input only and must not be logged or persisted.
 */
public interface SensitiveDataValidationPort {

    ValidationEvidence validate(Candidate candidate);

    record Candidate(String entityType, String matchedText, String contextText) {
        public Candidate {
            if (entityType == null || entityType.isBlank()) {
                throw new IllegalArgumentException("Entity type must not be blank");
            }
            if (matchedText == null || matchedText.isBlank()) {
                throw new IllegalArgumentException("Matched text must not be blank");
            }
            if (contextText == null) {
                contextText = "";
            }
        }
    }

    enum ValidationOutcome {
        SUPPORTS,
        CONTRADICTS,
        NOT_APPLICABLE
    }

    record ValidationEvidence(ValidationOutcome outcome, String validatorName, String validatorVersion) {
        public ValidationEvidence {
            Objects.requireNonNull(outcome, "Validation outcome must not be null");
            if (validatorName == null || validatorName.isBlank()) {
                validatorName = "unknown-validator";
            }
            if (validatorVersion == null || validatorVersion.isBlank()) {
                validatorVersion = "unknown";
            }
        }

        public static ValidationEvidence supports(String name, String version) {
            return new ValidationEvidence(ValidationOutcome.SUPPORTS, name, version);
        }

        public static ValidationEvidence contradicts(String name, String version) {
            return new ValidationEvidence(ValidationOutcome.CONTRADICTS, name, version);
        }

        public static ValidationEvidence notApplicable(String name, String version) {
            return new ValidationEvidence(ValidationOutcome.NOT_APPLICABLE, name, version);
        }
    }
}
