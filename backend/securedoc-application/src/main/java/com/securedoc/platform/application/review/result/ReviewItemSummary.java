package com.securedoc.platform.application.review.result;

import com.securedoc.platform.application.review.model.ReviewReason;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;

/**
 * Queue item for the focused Manual Review workbench. {@code foundText} is the only sensitive
 * value: it is read on demand for MANUAL_REVIEW callers, is null when it cannot be read safely,
 * and is left out of {@link #toString()} so it never reaches a log line.
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
        ReviewReason reason,
        ReviewFoundText foundText
) {

    public ReviewItemSummary withFoundText(ReviewFoundText text) {
        return new ReviewItemSummary(findingId, packageId, documentId, fileName, entityType,
                confidence, pageNumber, hasBoundingBox, boundingBox, reason, text);
    }

    @Override
    public String toString() {
        return "ReviewItemSummary[findingId=" + findingId + ", packageId=" + packageId
                + ", documentId=" + documentId + ", entityType=" + entityType
                + ", pageNumber=" + pageNumber + ", reason=" + reason
                + ", foundText=" + (foundText == null ? "absent" : "redacted") + "]";
    }
}
