package com.securedoc.platform.application.pipeline;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Task 0049: one page batch of a large document.
 *
 * @param jobId the pipeline job that reads or searches it
 * @param from  its first page (1-based)
 * @param to    its last page
 */
public record PageBatch(UUID jobId, int from, int to) {
    public PageBatch {
        Objects.requireNonNull(jobId, "jobId");
        if (from < 1 || to < from) throw new IllegalArgumentException("Invalid page range");
    }

    public Set<Integer> pages() {
        return IntStream.rangeClosed(from, to).boxed()
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    public boolean covers(int page) {
        return page >= from && page <= to;
    }
}
