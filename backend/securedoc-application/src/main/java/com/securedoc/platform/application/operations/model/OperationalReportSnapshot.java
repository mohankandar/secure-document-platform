package com.securedoc.platform.application.operations.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Business/operational report snapshot backed by structured persisted facts. */
public record OperationalReportSnapshot(
        Instant generatedAt,
        Period period,
        Executive executive,
        Intake intake,
        Processing processing,
        Export export,
        Retention retention,
        Timing timing,
        Backlog backlog,
        Usage usage,
        ServiceLevel serviceLevel,
        List<DimensionBreakdown> dimensions
) {
    public OperationalReportSnapshot {
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(period);
        Objects.requireNonNull(executive);
        Objects.requireNonNull(intake);
        Objects.requireNonNull(processing);
        Objects.requireNonNull(export);
        Objects.requireNonNull(retention);
        Objects.requireNonNull(timing);
        Objects.requireNonNull(backlog);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(serviceLevel);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public record Period(Instant from, Instant to, Instant previousFrom, Instant previousTo,
                         String source, String outcome) {}

    public record Executive(long batches, long previousBatches, long documents, long pages,
                            long completed, long previousCompleted, long delivered, long previousDelivered,
                            long periodIntakeDelivered, long previousPeriodIntakeDelivered,
                            double completionRate, double deliveryRate, double periodIntakeDeliveryRate, double medianDeliveryHours,
                            double p95DeliveryHours, double straightThroughRate, double reviewRate,
                            long currentBacklog, long currentAttention, double oldestBacklogHours,
                            List<TrendPoint> trend) {}

    public record TrendPoint(Instant bucket, long received, long delivered) {}

    public record Intake(long totalBatches, long manual, long api, long watchedFolder, long scanner,
                         long waitingForTrigger, long duplicateObservations, long rejectedObservations) {}

    public record Processing(long processing, long needsReview, long needsApproval,
                             long completed, long technicalAttention, long rejected) {}

    public record Export(long exportedPackages, long successfulExportCycles,
                         long pendingFinalApproval) {}

    public record Retention(long originalsDueWithin24Hours, long redactedCopiesDueWithin24Hours,
                            long technicalHoldsDueWithin24Hours, long purgedPackages,
                            long originalsOverdue, long redactedCopiesOverdue) {}

    /** Timings are reported in hours; zero means there is no eligible sample in the selected period. */
    public record Timing(double medianTurnaroundHours, double p95TurnaroundHours,
                         double medianActiveProcessingHours, double p95ActiveProcessingHours,
                         double medianReviewWaitHours, double medianReviewHandlingHours,
                         long turnaroundSamples, long activeProcessingSamples,
                         long reviewWaitSamples, long reviewHandlingSamples) {}

    /** Current open-work aging buckets. They are point-in-time measures and do not depend on flow period. */
    public record Backlog(long underOneHour, long oneToFourHours, long fourToTwentyFourHours,
                          long oneToThreeDays, long overThreeDays, double oldestHours) {}

    public record Usage(long packages, long documents, long pages,
                        double averageDocumentsPerPackage, double averagePagesPerPackage,
                        long reviewedPackages, long straightThroughPackages, long reprocessedPackages) {}
    public record ServiceLevel(
            int deliveryTargetMinutes, double deliveryTargetPercent,
            double deliveryWithinTargetRate, long deliveryEligible, long deliveryBreaches,
            double straightThroughTargetPercent, double straightThroughActualPercent,
            int reviewWaitTargetMinutes, double reviewWithinTargetRate,
            long reviewEligible, long reviewBreaches
    ) {}

    public record DimensionBreakdown(String key, String label, List<DimensionValue> values) {
        public DimensionBreakdown { values = values == null ? List.of() : List.copyOf(values); }
    }

    public record DimensionValue(String value, long packages, long delivered, double deliveryRate,
                                 double medianTurnaroundHours) {}

}
