package com.securedoc.platform.worker.processing;

/**
 * Configuration for the document processing worker.
 * Plain POJO — bound by the API/infrastructure layer via @ConfigurationProperties.
 * Disabled by default.
 */
public class ProcessingWorkerConfig {

    /** Whether the worker is enabled. */
    private boolean enabled = false;

    /** Polling interval in seconds. */
    private int pollIntervalSeconds = 30;

    /** Maximum documents to process per poll cycle. */
    private int batchSize = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
