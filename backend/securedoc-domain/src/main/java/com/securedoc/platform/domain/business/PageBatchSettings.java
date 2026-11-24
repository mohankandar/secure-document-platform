package com.securedoc.platform.domain.business;

/**
 * Task 0049: how a large document is split into page batches for Text and Find (staged pipeline). A document of at
 * most {@code size} pages is one job.
 *
 * @param size    pages per batch
 * @param overlap pages a Find batch shares with the next one, so nothing around a batch's edge is missed
 */
public record PageBatchSettings(int size, int overlap) {
    public static final int MAX_SIZE = 500;
    public static final int MAX_OVERLAP = 20;
    public static final PageBatchSettings DEFAULTS = new PageBatchSettings(20, 2);

    public PageBatchSettings {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page batch size must be between 1 and " + MAX_SIZE);
        }
        if (overlap < 0 || overlap > MAX_OVERLAP || overlap >= size) {
            throw new IllegalArgumentException("Page batch overlap must be between 0 and " + MAX_OVERLAP
                    + " and smaller than the batch size");
        }
    }
}
