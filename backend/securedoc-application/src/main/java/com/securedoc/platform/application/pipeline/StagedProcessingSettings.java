package com.securedoc.platform.application.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the staged Text / Find / Redact jobs (task 0048).
 *
 * @param mode          inline (default) or staged
 * @param claimLease    how long a claim lasts without renewal; a worker renews it every third of this
 * @param maxAttempts   claims that may run out (a server stopping mid-job) before the case stops with LV-PRC-008
 * @param textWorkers   Text workers on this server
 * @param findWorkers   Find workers on this server
 * @param redactWorkers Redact workers on this server
 * @param visionWorkers Vision workers on this server (task 0064)
 * @param pollInterval  how often an idle worker looks for a job, and how often expired claims are swept
 */
public record StagedProcessingSettings(PipelineMode mode, Duration claimLease, int maxAttempts, int textWorkers,
                                       int findWorkers, int redactWorkers, Duration pollInterval,
                                       int visionWorkers) {

    /** The task 0048 shape, with the default number of vision workers. */
    public StagedProcessingSettings(PipelineMode mode, Duration claimLease, int maxAttempts, int textWorkers,
                                    int findWorkers, int redactWorkers, Duration pollInterval) {
        this(mode, claimLease, maxAttempts, textWorkers, findWorkers, redactWorkers, pollInterval, 2);
    }

    public StagedProcessingSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(claimLease, "claimLease");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (claimLease.toMillis() < 3000) throw new IllegalArgumentException("claim lease must be >= 3 seconds");
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be >= 1");
        if (textWorkers < 1 || findWorkers < 1 || redactWorkers < 1 || visionWorkers < 1) {
            throw new IllegalArgumentException("each stage needs at least one worker");
        }
        if (pollInterval.toMillis() < 100) throw new IllegalArgumentException("poll interval must be >= 100 ms");
    }

    public static StagedProcessingSettings defaults(PipelineMode mode) {
        return new StagedProcessingSettings(mode, Duration.ofMinutes(2), 3, 2, 2, 2, Duration.ofSeconds(1));
    }

    public boolean staged() {
        return mode == PipelineMode.STAGED;
    }
}
