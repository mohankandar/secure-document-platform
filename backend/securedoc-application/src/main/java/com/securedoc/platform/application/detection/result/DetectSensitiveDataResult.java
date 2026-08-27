package com.securedoc.platform.application.detection.result;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.policy.PolicyDecision;

import java.util.Map;
import java.util.Objects;

/**
 * Result of sensitive-data detection and policy evaluation.
 * Contains safe summary metadata only — never raw sensitive values.
 */
public record DetectSensitiveDataResult(
        DocumentId documentId,
        DocumentStatus resultingStatus,
        int findingCount,
        Map<String, Integer> findingsByType,
        boolean requiresRedaction,
        PolicyDecision overallDecision
) {

    public DetectSensitiveDataResult {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(resultingStatus, "ResultingStatus must not be null");
        Objects.requireNonNull(overallDecision, "OverallDecision must not be null");
        if (findingsByType == null) {
            findingsByType = Map.of();
        } else {
            findingsByType = Map.copyOf(findingsByType);
        }
    }

    public static DetectSensitiveDataResult of(DocumentId documentId, DocumentStatus resultingStatus,
                                                int findingCount, Map<String, Integer> findingsByType,
                                                boolean requiresRedaction, PolicyDecision overallDecision) {
        return new DetectSensitiveDataResult(documentId, resultingStatus, findingCount,
                findingsByType, requiresRedaction, overallDecision);
    }
}
