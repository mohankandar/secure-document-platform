package com.securedoc.platform.application.operations.model;

import java.time.Instant;
import java.util.Objects;

/** Compact V1 operational-report snapshot backed by structured persisted facts. */
public record OperationalReportSnapshot(
        Instant generatedAt,
        Intake intake,
        Processing processing,
        Export export,
        Retention retention
) {
    public OperationalReportSnapshot {
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(intake);
        Objects.requireNonNull(processing);
        Objects.requireNonNull(export);
        Objects.requireNonNull(retention);
    }

    public record Intake(long totalBatches, long manual, long api, long watchedFolder,
                         long waitingForTrigger, long duplicateObservations, long rejectedObservations) {}

    public record Processing(long processing, long needsReview, long needsApproval,
                             long completed, long technicalAttention, long rejected) {}

    public record Export(long exportedPackages, long successfulExportCycles,
                         long pendingFinalApproval) {}

    public record Retention(long originalsDueWithin24Hours, long redactedCopiesDueWithin24Hours,
                            long technicalHoldsDueWithin24Hours, long purgedPackages) {}
}
