package com.securedoc.platform.application.manual.result;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.List;
import java.util.Set;

public record ManualFallbackDetail(
        PackageId packageId,
        String status,
        List<ManualFallbackDocumentSummary> documents,
        List<ManualRedactionRegionSummary> regions,
        Set<EntityType> enabledEntityTypes) {}
