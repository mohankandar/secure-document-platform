package com.securedoc.platform.scanbridge.model;

import java.time.Instant;
import java.util.UUID;

/** Safe technical facts about the one consolidated PDF produced by a scan job. */
public record PreparedScan(
        UUID sourceDocumentId,
        String fileName,
        long sizeBytes,
        String sha256,
        Instant receivedAt,
        String workReference,
        Integer pageCount
) {
    public PreparedScan(UUID sourceDocumentId, String fileName, long sizeBytes, String sha256,
                        Instant receivedAt, String workReference) {
        this(sourceDocumentId, fileName, sizeBytes, sha256, receivedAt, workReference, null);
    }
}
