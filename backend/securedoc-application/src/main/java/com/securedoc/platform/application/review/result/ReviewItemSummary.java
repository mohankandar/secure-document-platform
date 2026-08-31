package com.securedoc.platform.application.review.result;

import com.securedoc.platform.application.review.model.ReviewReason;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;

/**
 * Safe queue item for the focused Manual Review workbench. Raw sensitive text/snippets are
 * deliberately excluded; the UI uses the relevant rendered PDF page for context.
 */
public record ReviewItemSummary(
        FindingId findingId,
        PackageId packageId,
        DocumentId documentId,
        String fileName,
        EntityType entityType,
        double confidence,
        int pageNumber,
        boolean hasBoundingBox,
        ReviewBoundingBoxSummary boundingBox,
        ReviewReason reason
) {}
