package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;

public record OperationalReportsResponse(Instant generatedAt, Period period, Executive executive, Intake intake, Processing processing,
                                         Export export, Retention retention) {
    public record Period(Instant from, Instant to, Instant previousFrom, Instant previousTo,
                         String source, String outcome) {}
    public record Executive(long batches, long previousBatches, long documents, long pages,
                            long completed, long previousCompleted, long delivered, long previousDelivered,
                            double completionRate, double deliveryRate, double medianDeliveryHours,
                            double p95DeliveryHours, double straightThroughRate, double reviewRate,
                            long currentBacklog, long currentAttention, double oldestBacklogHours,
                            List<TrendPoint> trend) {}
    public record TrendPoint(Instant bucket, long received, long delivered) {}
    public record Intake(long totalBatches, long manual, long api, long watchedFolder,
                         long waitingForTrigger, long duplicateObservations, long rejectedObservations) {}
    public record Processing(long processing, long needsReview, long needsApproval,
                             long completed, long technicalAttention, long rejected) {}
    public record Export(long exportedPackages, long successfulExportCycles, long pendingFinalApproval) {}
    public record Retention(long originalsDueWithin24Hours, long redactedCopiesDueWithin24Hours,
                            long technicalHoldsDueWithin24Hours, long purgedPackages,
                            long originalsOverdue, long redactedCopiesOverdue) {}
}
