package com.securedoc.platform.application.pipeline;

import java.util.function.Supplier;

/**
 * Task 0052: marks the current thread as running one attempt of an export job, whatever export-mode says now. A delivery
 * adapter then tries once per call: the job spaces its tries, honours a 429's wait, and a resend is one attempt.
 */
public final class ExportJobAttempt {
    private static final ThreadLocal<Boolean> RUNNING = new ThreadLocal<>();

    private ExportJobAttempt() {
    }

    public static boolean running() {
        return RUNNING.get() != null;
    }

    public static <T> T run(Supplier<T> attempt) {
        RUNNING.set(Boolean.TRUE);
        try {
            return attempt.get();
        } finally {
            RUNNING.remove();
        }
    }
}
