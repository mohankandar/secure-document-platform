package com.securedoc.platform.application.business.admin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-owned reporting configuration. Values are aggregate-only and contain no document content. */
public record ReportingConfiguration(
        List<Dimension> dimensions,
        int deliveryTargetMinutes,
        double deliveryTargetPercent,
        double straightThroughTargetPercent,
        int reviewWaitTargetMinutes
) {
    public static final int DEFAULT_DELIVERY_TARGET_MINUTES = 240;
    public static final double DEFAULT_DELIVERY_TARGET_PERCENT = 95.0;
    public static final double DEFAULT_STRAIGHT_THROUGH_TARGET_PERCENT = 90.0;
    public static final int DEFAULT_REVIEW_WAIT_TARGET_MINUTES = 60;

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
    }

    public static ReportingConfiguration defaults() {
        return new ReportingConfiguration(List.of(), DEFAULT_DELIVERY_TARGET_MINUTES,
                DEFAULT_DELIVERY_TARGET_PERCENT, DEFAULT_STRAIGHT_THROUGH_TARGET_PERCENT,
                DEFAULT_REVIEW_WAIT_TARGET_MINUTES);
    }

    public record Dimension(UUID definitionId, String key, String label, boolean enabled, int displayOrder) {
        public Dimension {
            Objects.requireNonNull(definitionId);
            Objects.requireNonNull(key);
            Objects.requireNonNull(label);
        }
    }
}
