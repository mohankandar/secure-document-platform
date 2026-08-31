package com.securedoc.platform.api.dto;

import java.time.Instant;

public record OperationalReportsResponse(Instant generatedAt, Intake intake, Processing processing,
                                         Export export, Retention retention) {
    public record Intake(long totalBatches, long manual, long api, long watchedFolder,
                         long waitingForTrigger, long duplicateObservations, long rejectedObservations) {}
    public record Processing(long processing, long needsReview, long needsApproval,
                             long completed, long technicalAttention, long rejected) {}
    public record Export(long exportedPackages, long successfulExportCycles, long pendingFinalApproval) {}
    public record Retention(long originalsDueWithin24Hours, long redactedCopiesDueWithin24Hours,
                            long technicalHoldsDueWithin24Hours, long purgedPackages) {}
}
