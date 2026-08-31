package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependencyException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Small synchronous V1 retry helper. It retries only explicitly typed retryable technical
 * failures; business/content uncertainty and ordinary validation failures are never retried.
 */
public final class SelectiveRetryExecutor {

    private final int maxAttempts;
    private final Duration initialBackoff;

    public SelectiveRetryExecutor(int maxAttempts, Duration initialBackoff) {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff);
    }

    public <T> T execute(Callable<T> action) {
        Objects.requireNonNull(action);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.call();
            } catch (TechnicalDependencyException e) {
                if (!e.retryable() || attempt >= maxAttempts) {
                    // Preserve attempts already consumed by a lower-level targeted retry
                    // strategy (for example Ollama page-level retries).
                    throw e.withAttempts(Math.max(attempt, e.attempts()));
                }
                sleep(attempt);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void executeVoid(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    private void sleep(int completedAttempt) {
        long base = Math.max(0L, initialBackoff.toMillis());
        long millis = Math.min(5_000L, base * Math.max(1, completedAttempt));
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry backoff interrupted", e);
        }
    }
}
