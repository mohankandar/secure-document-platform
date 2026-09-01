package com.securedoc.platform.application.review.result;

import java.util.List;

/** Current actionable Manual Review queue, optionally scoped to one package. */
public record ReviewQueueResult(List<ReviewItemSummary> items, int totalItems) {
    public ReviewQueueResult {
        items = List.copyOf(items);
        if (totalItems < items.size()) {
            throw new IllegalArgumentException("Total review item count cannot be smaller than returned item count");
        }
    }
}
