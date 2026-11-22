package com.securedoc.platform.application.pipeline;

/**
 * Task 0034: how long finished pipeline jobs (DONE, STOPPED) and cleared reachability alerts are kept before the
 * clean-up removes them, and how many job rows one delete removes at most.
 *
 * <ul>
 *   <li>{@code keepFinishedDays}: {@code securedoc.processing.export-jobs.keep-finished-days}, default 90, 7 to 3650.</li>
 *   <li>{@code batchSize}: {@code securedoc.processing.export-jobs.cleanup-batch-size}, default 1,000, 1 to 10,000.</li>
 * </ul>
 * A value outside its range stops start-up (LV-SYS-005); the message names the setting and its range, never the value.
 */
public record FinishedJobCleanupSettings(int keepFinishedDays, int batchSize) {

    public static final String KEEP_FINISHED_DAYS = "securedoc.processing.export-jobs.keep-finished-days";
    public static final String BATCH_SIZE = "securedoc.processing.export-jobs.cleanup-batch-size";
    public static final int DEFAULT_KEEP_FINISHED_DAYS = 90;
    public static final int MIN_KEEP_FINISHED_DAYS = 7;
    public static final int MAX_KEEP_FINISHED_DAYS = 3650;
    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int MAX_BATCH_SIZE = 10_000;

    public FinishedJobCleanupSettings {
        if (keepFinishedDays < MIN_KEEP_FINISHED_DAYS || keepFinishedDays > MAX_KEEP_FINISHED_DAYS) {
            throw new IllegalArgumentException(KEEP_FINISHED_DAYS + " must be between " + MIN_KEEP_FINISHED_DAYS
                    + " and " + MAX_KEEP_FINISHED_DAYS + " days");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(BATCH_SIZE + " must be between 1 and " + MAX_BATCH_SIZE);
        }
    }

    public static FinishedJobCleanupSettings defaults() {
        return new FinishedJobCleanupSettings(DEFAULT_KEEP_FINISHED_DAYS, DEFAULT_BATCH_SIZE);
    }
}
