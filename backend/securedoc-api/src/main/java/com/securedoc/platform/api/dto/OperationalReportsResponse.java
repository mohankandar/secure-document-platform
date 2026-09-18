package com.securedoc.platform.api.dto;

import java.time.Instant;
import java.util.List;

public record OperationalReportsResponse(Instant generatedAt, Period period, Executive executive, Intake intake, Processing processing,
                                         Export export, Retention retention, Timing timing, Backlog backlog, Usage usage,
                                         ServiceLevel serviceLevel, List<DimensionBreakdown> dimensions) {
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
    public record Export(long exportedPackages, long successfulExportCycles, long pendingFinalApproval) {}
    public record Retention(long originalsDueWithin24Hours, long redactedCopiesDueWithin24Hours,
                            long technicalHoldsDueWithin24Hours, long purgedPackages,
                            long originalsOverdue, long redactedCopiesOverdue) {}
    public record Timing(double medianTurnaroundHours, double p95TurnaroundHours,
                         double medianActiveProcessingHours, double p95ActiveProcessingHours,
                         double medianReviewWaitHours, double medianReviewHandlingHours,
                         long turnaroundSamples, long activeProcessingSamples,
                         long reviewWaitSamples, long reviewHandlingSamples) {}
    public record Backlog(long underOneHour, long oneToFourHours, long fourToTwentyFourHours,
                          long oneToThreeDays, long overThreeDays, double oldestHours) {}
    public record Usage(long packages, long documents, long pages,
                        double averageDocumentsPerPackage, double averagePagesPerPackage,
                        long reviewedPackages, long straightThroughPackages, long reprocessedPackages) {}
    public record ServiceLevel(int deliveryTargetMinutes, double deliveryTargetPercent,
                               double deliveryWithinTargetRate, long deliveryEligible, long deliveryBreaches,
                               double straightThroughTargetPercent, double straightThroughActualPercent,
                               int reviewWaitTargetMinutes, double reviewWithinTargetRate,
                               long reviewEligible, long reviewBreaches) {}
    public record DimensionBreakdown(String key, String label, List<DimensionValue> values) {}
    public record DimensionValue(String value, long packages, long delivered, double deliveryRate,
                                 double medianTurnaroundHours) {}

}
