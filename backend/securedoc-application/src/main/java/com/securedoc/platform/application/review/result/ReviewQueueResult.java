package com.securedoc.platform.application.review.result;

import java.util.List;

/**
 * Current actionable Manual Review queue, optionally scoped to one package.
 * {@code autoRedactThreshold} is the tenant policy level (0..1) from which findings are redacted
 * without a person, so the UI can say why a person is asked; null when not known.
 */
public record ReviewQueueResult(List<ReviewItemSummary> items, int totalItems, Double autoRedactThreshold) {
    public ReviewQueueResult {
        items = List.copyOf(items);
        if (totalItems < items.size()) {
            throw new IllegalArgumentException("Total review item count cannot be smaller than returned item count");
        }
    }

    public ReviewQueueResult(List<ReviewItemSummary> items, int totalItems) {
        this(items, totalItems, null);
    }
}
