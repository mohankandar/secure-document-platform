package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.business.PageBatchSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 0049: the page ranges of a document's batches. Text batches do not overlap (a page's text does not depend on
 * its neighbours); Find batches share {@code overlap} pages with the next one.
 */
public final class PageBatchPlan {

    /** Pages {@code from} to {@code to}, 1-based, both included. */
    public record Range(int from, int to) { }

    private PageBatchPlan() {
    }

    /** Empty when the document is one job. */
    public static List<Range> text(int pages, PageBatchSettings settings) {
        return ranges(pages, settings.size(), settings.size());
    }

    /** Empty when the document is one job. */
    public static List<Range> find(int pages, PageBatchSettings settings) {
        return ranges(pages, settings.size(), settings.size() - settings.overlap());
    }

    private static List<Range> ranges(int pages, int size, int step) {
        List<Range> ranges = new ArrayList<>();
        if (pages <= size) return ranges;
        for (int from = 1; ; from += step) {
            int to = Math.min(pages, from + size - 1);
            ranges.add(new Range(from, to));
            if (to == pages) return ranges;
        }
    }
}
