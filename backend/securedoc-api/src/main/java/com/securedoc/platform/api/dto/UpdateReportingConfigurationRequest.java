package com.securedoc.platform.api.dto;

import java.util.List;
import java.util.UUID;

public record UpdateReportingConfigurationRequest(
        List<UUID> enabledDimensionDefinitionIds,
        int deliveryTargetMinutes,
        double deliveryTargetPercent,
        double straightThroughTargetPercent,
        int reviewWaitTargetMinutes,
        /** 0 to 100. Left out by older callers, in which case the stored value is kept. */
        Integer reviewWaitTargetPercent,
        /** Task 0020 B2: 0.5 to 30 in steps of 0.5. Left out by older callers, in which case the stored value is kept. */
        Double minutesPerPage
) {}
