package com.securedoc.platform.application.manual.result;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;

public record ManualRedactionRegionSummary(
        FindingId findingId, DocumentId documentId, int pageNumber, EntityType entityType,
        double left, double top, double width, double height) {}
