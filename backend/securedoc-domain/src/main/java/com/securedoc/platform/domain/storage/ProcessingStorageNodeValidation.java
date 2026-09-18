package com.securedoc.platform.domain.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProcessingStorageNodeValidation(
        String nodeId,
        UUID processInstanceId,
        UUID providerId,
        ProcessingStorageValidationStatus status,
        String failureCode,
        Instant checkedAt) {
    public ProcessingStorageNodeValidation {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("Node id is required");
        Objects.requireNonNull(processInstanceId, "Process instance id is required");
        Objects.requireNonNull(providerId, "Provider id is required");
        Objects.requireNonNull(status, "Validation status is required");
        Objects.requireNonNull(checkedAt, "Checked time is required");
    }
}
