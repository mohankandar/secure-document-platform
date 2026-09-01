package com.securedoc.platform.application.technical.model;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;

/**
 * Safe, tenant-scoped technical exception metadata. It intentionally contains no document values,
 * raw stack traces, credentials, tokens, or provider secrets.
 */
public record TechnicalExceptionDetail(
        PackageId packageId,
        DocumentId documentId,
        String status,
        String failureCategory,
        String failedStage,
        String dependency,
        String failureCode,
        boolean retryable,
        int attempts,
        Instant failedAt,
        String correlationId,
        Instant originalRetainedUntil,
        String explanation
) {
}
