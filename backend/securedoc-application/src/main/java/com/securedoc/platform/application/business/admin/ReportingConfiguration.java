package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.operations.TimeSavedRule;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-owned reporting configuration. Values are aggregate-only and contain no document content. */
public record ReportingConfiguration(
        List<Dimension> dimensions,
        int deliveryTargetMinutes,
        double deliveryTargetPercent,
        double straightThroughTargetPercent,
        int reviewWaitTargetMinutes,
        int reviewWaitTargetPercent,
        double minutesPerPage
) {
    public static final int DEFAULT_DELIVERY_TARGET_MINUTES = 240;
    public static final double DEFAULT_DELIVERY_TARGET_PERCENT = 95.0;
    public static final double DEFAULT_STRAIGHT_THROUGH_TARGET_PERCENT = 90.0;
    public static final int DEFAULT_REVIEW_WAIT_TARGET_MINUTES = 60;
    public static final int DEFAULT_REVIEW_WAIT_TARGET_PERCENT = 80;
    /** Task 0020 B2: minutes a person would take to check one page by hand (0.5 to 30, steps of 0.5). */
    public static final double DEFAULT_MINUTES_PER_PAGE = TimeSavedRule.DEFAULT_MINUTES_PER_PAGE;

    public ReportingConfiguration {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        if (deliveryTargetMinutes < 1 || deliveryTargetMinutes > 10080) {
            throw new IllegalArgumentException("deliveryTargetMinutes must be between 1 and 10080");
        }
        if (deliveryTargetPercent < 1 || deliveryTargetPercent > 100) {
            throw new IllegalArgumentException("deliveryTargetPercent must be between 1 and 100");
        }
        if (straightThroughTargetPercent < 0 || straightThroughTargetPercent > 100) {
            throw new IllegalArgumentException("straightThroughTargetPercent must be between 0 and 100");
        }
        if (reviewWaitTargetMinutes < 1 || reviewWaitTargetMinutes > 10080) {
            throw new IllegalArgumentException("reviewWaitTargetMinutes must be between 1 and 10080");
        }
        if (reviewWaitTargetPercent < 0 || reviewWaitTargetPercent > 100) {
            throw new IllegalArgumentException("reviewWaitTargetPercent must be between 0 and 100");
        }
        if (!TimeSavedRule.allowedMinutesPerPage(minutesPerPage)) {
            throw new IllegalArgumentException("minutesPerPage must be from 0.5 to 30, in steps of 0.5");
        }
    }

    public static ReportingConfiguration defaults() {
        return new ReportingConfiguration(List.of(), DEFAULT_DELIVERY_TARGET_MINUTES,
                DEFAULT_DELIVERY_TARGET_PERCENT, DEFAULT_STRAIGHT_THROUGH_TARGET_PERCENT,
                DEFAULT_REVIEW_WAIT_TARGET_MINUTES, DEFAULT_REVIEW_WAIT_TARGET_PERCENT, DEFAULT_MINUTES_PER_PAGE);
    }

    public record Dimension(UUID definitionId, String key, String label, boolean enabled, int displayOrder) {
        public Dimension {
            Objects.requireNonNull(definitionId);
            Objects.requireNonNull(key);
            Objects.requireNonNull(label);
        }
    }
}
