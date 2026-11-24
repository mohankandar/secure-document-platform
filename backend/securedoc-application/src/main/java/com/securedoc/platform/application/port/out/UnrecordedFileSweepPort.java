package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Map;

/** Task 0049: removes stored text and redacted files that no record points to. */
public interface UnrecordedFileSweepPort {

    /** Files removed for one entity. Numbers only. */
    record Removed(int textFiles, int redactedFiles) {
        public int total() {
            return textFiles + redactedFiles;
        }
    }

    /**
     * Removes at most {@code limit} text and redacted files last written before {@code olderThan} that no record points
     * to. A file whose place, document or owner is not certain is kept.
     */
    Map<TenantId, Removed> removeUnrecorded(Instant olderThan, int limit);
}
