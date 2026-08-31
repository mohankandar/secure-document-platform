package com.securedoc.platform.infrastructure.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Small V1 runtime resilience settings; IT Admin UI is intentionally deferred. */
@Component
@ConfigurationProperties(prefix = "securedoc.resilience")
public class TechnicalResilienceProperties {
    private int maxAttempts = 2;
    private long initialBackoffMs = 250;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }
}
