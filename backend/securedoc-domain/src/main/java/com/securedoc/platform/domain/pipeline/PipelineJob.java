package com.securedoc.platform.domain.pipeline;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pipeline job (task 0027). Codes, ids and times only: never a file name, a value or a message.
 *
 * @param destinationId the integration connection the job sends to; null for the local export folder
 * @param itemId        for SPLIT_EXPORT the derived delivery id; null otherwise
 * @param exportCycle   the case run (export cycle) the job belongs to
 * @param claimToken    the fencing token: increased on every claim; a result is saved only with the current one
 * @param lastLvCode    the last LexaVault code (for example LV-DLV-002)
 * @param lastDetail    an internal code only (for example DELIVERY_IO, or the failure's own code under LV-DLV-015)
 */
public record PipelineJob(
        UUID id,
        TenantId tenantId,
        PipelineStage stage,
        PackageId packageId,
        DocumentId documentId,
        IntegrationConnectionId destinationId,
        UUID itemId,
        int exportCycle,
        PipelinePriority priority,
        PipelineJobStatus status,
        int attempts,
        Instant nextAttemptAt,
        String claimOwner,
        Instant claimUntil,
        long claimToken,
        String lastLvCode,
        String lastDetail,
        Instant createdAt,
        Instant updatedAt) {

    public PipelineJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (exportCycle < 1) throw new IllegalArgumentException("exportCycle must be >= 1");
        if (attempts < 0) throw new IllegalArgumentException("attempts must be >= 0");
        if (stage == PipelineStage.SPLIT_EXPORT && itemId == null) {
            throw new IllegalArgumentException("A split export job needs its derived delivery id");
        }
    }

    /** A new READY job, NORMAL priority, no attempts yet. */
    public static PipelineJob ready(TenantId tenantId, PipelineStage stage, PackageId packageId, DocumentId documentId,
                                    IntegrationConnectionId destinationId, UUID itemId, int exportCycle, Instant now) {
        return new PipelineJob(UUID.randomUUID(), tenantId, stage, packageId, documentId, destinationId, itemId,
                exportCycle, PipelinePriority.NORMAL, PipelineJobStatus.READY, 0, now, null, null, 0L,
                null, null, now, now);
    }
}
