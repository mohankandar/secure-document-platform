package com.securedoc.platform.domain.pipeline;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pipeline job (task 0027; retry fields task 0028). Codes, ids and times only: never a file name, a value or a
 * message.
 *
 * @param destinationId the export destination the job sends to (an integration connection, or the local export
 *                      folder's derived id, see {@link ExportDestinations}); null when it could not be resolved
 * @param itemId        for SPLIT_EXPORT the derived delivery id; null otherwise
 * @param exportCycle   the case run (export cycle) the job belongs to
 * @param claimToken    the fencing token: increased on every claim; a result is saved only with the current one
 * @param lastLvCode    the last LexaVault code (for example LV-DLV-002)
 * @param lastDetail    an internal code only (for example DELIVERY_IO, or the failure's own code under LV-DLV-015)
 * @param resend        the next run is a person's resend: one attempt, a failure goes to IT as LV-DLV-015
 * @param rebuilt       the export was rebuilt once from the stored redacted copies
 * @param claimExpiries claims that ran out (a server stopped mid-job)
 * @param firstFailedAt when the job first failed
 * @param lastFailedAt  when the job last failed
 * @param pageFrom      task 0049: the first page of a page batch; null for the whole document
 * @param pageTo        the last page of a page batch; null for the whole document
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
        Instant updatedAt,
        boolean resend,
        boolean rebuilt,
        int claimExpiries,
        Instant firstFailedAt,
        Instant lastFailedAt,
        Integer pageFrom,
        Integer pageTo) {

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
        if (claimExpiries < 0) throw new IllegalArgumentException("claimExpiries must be >= 0");
        if ((pageFrom == null) != (pageTo == null) || (pageFrom != null && (pageFrom < 1 || pageTo < pageFrom))) {
            throw new IllegalArgumentException("Invalid page range");
        }
        if (stage == PipelineStage.SPLIT_EXPORT && itemId == null) {
            throw new IllegalArgumentException("A split export job needs its derived delivery id");
        }
    }

    /** The task 0027 shape: no resend, not rebuilt, no claim expiries, no failure times. */
    public PipelineJob(UUID id, TenantId tenantId, PipelineStage stage, PackageId packageId, DocumentId documentId,
                       IntegrationConnectionId destinationId, UUID itemId, int exportCycle, PipelinePriority priority,
                       PipelineJobStatus status, int attempts, Instant nextAttemptAt, String claimOwner,
                       Instant claimUntil, long claimToken, String lastLvCode, String lastDetail, Instant createdAt,
                       Instant updatedAt) {
        this(id, tenantId, stage, packageId, documentId, destinationId, itemId, exportCycle, priority, status, attempts,
                nextAttemptAt, claimOwner, claimUntil, claimToken, lastLvCode, lastDetail, createdAt, updatedAt,
                false, false, 0, null, null, null, null);
    }

    /** The task 0028 shape: the whole document. */
    public PipelineJob(UUID id, TenantId tenantId, PipelineStage stage, PackageId packageId, DocumentId documentId,
                       IntegrationConnectionId destinationId, UUID itemId, int exportCycle, PipelinePriority priority,
                       PipelineJobStatus status, int attempts, Instant nextAttemptAt, String claimOwner,
                       Instant claimUntil, long claimToken, String lastLvCode, String lastDetail, Instant createdAt,
                       Instant updatedAt, boolean resend, boolean rebuilt, int claimExpiries, Instant firstFailedAt,
                       Instant lastFailedAt) {
        this(id, tenantId, stage, packageId, documentId, destinationId, itemId, exportCycle, priority, status, attempts,
                nextAttemptAt, claimOwner, claimUntil, claimToken, lastLvCode, lastDetail, createdAt, updatedAt,
                resend, rebuilt, claimExpiries, firstFailedAt, lastFailedAt, null, null);
    }

    /** True for one page batch of a large document (task 0049). */
    public boolean isPageBatch() {
        return pageFrom != null;
    }

    /** A new READY job for one page batch of a document, NORMAL priority (task 0049). */
    public static PipelineJob readyBatch(TenantId tenantId, PipelineStage stage, PackageId packageId,
                                         DocumentId documentId, int pageFrom, int pageTo, int exportCycle,
                                         Instant now) {
        return new PipelineJob(UUID.randomUUID(), tenantId, stage, packageId, documentId, null, null, exportCycle,
                PipelinePriority.NORMAL, PipelineJobStatus.READY, 0, now, null, null, 0L, null, null, now, now, false,
                false, 0, null, null, pageFrom, pageTo);
    }

    /** A new READY job, NORMAL priority, no attempts yet. */
    public static PipelineJob ready(TenantId tenantId, PipelineStage stage, PackageId packageId, DocumentId documentId,
                                    IntegrationConnectionId destinationId, UUID itemId, int exportCycle, Instant now) {
        return new PipelineJob(UUID.randomUUID(), tenantId, stage, packageId, documentId, destinationId, itemId,
                exportCycle, PipelinePriority.NORMAL, PipelineJobStatus.READY, 0, now, null, null, 0L,
                null, null, now, now);
    }
}
