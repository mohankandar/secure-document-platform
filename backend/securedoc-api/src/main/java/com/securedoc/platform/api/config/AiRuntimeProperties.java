package com.securedoc.platform.api.config;

/** Runtime selection for the provider-neutral sensitive-content analysis port. */
public class AiRuntimeProperties {
    private String provider = "ollama";
    private String baseUrl = "http://localhost:11434";
    private String model = "qwen2.5:7b";
    private int timeoutSeconds = 60;
    private int parallelism = 2;
    private int pageMaxAttempts = 2;
    private long pageRetryBackoffMs = 500;

    private boolean secondaryEnabled = false;
    private String secondaryProvider = "ollama";
    private String secondaryBaseUrl = "http://localhost:11434";
    private String secondaryModel = "";
    private int secondaryTimeoutSeconds = 60;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getParallelism() { return parallelism; }
    public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    public int getPageMaxAttempts() { return pageMaxAttempts; }
    public void setPageMaxAttempts(int pageMaxAttempts) { this.pageMaxAttempts = pageMaxAttempts; }
    public long getPageRetryBackoffMs() { return pageRetryBackoffMs; }
    public void setPageRetryBackoffMs(long pageRetryBackoffMs) { this.pageRetryBackoffMs = pageRetryBackoffMs; }

    public boolean isSecondaryEnabled() { return secondaryEnabled; }
    public void setSecondaryEnabled(boolean secondaryEnabled) { this.secondaryEnabled = secondaryEnabled; }
    public String getSecondaryProvider() { return secondaryProvider; }
    public void setSecondaryProvider(String secondaryProvider) { this.secondaryProvider = secondaryProvider; }
    public String getSecondaryBaseUrl() { return secondaryBaseUrl; }
    public void setSecondaryBaseUrl(String secondaryBaseUrl) { this.secondaryBaseUrl = secondaryBaseUrl; }
    public String getSecondaryModel() { return secondaryModel; }
    public void setSecondaryModel(String secondaryModel) { this.secondaryModel = secondaryModel; }
    public int getSecondaryTimeoutSeconds() { return secondaryTimeoutSeconds; }
    public void setSecondaryTimeoutSeconds(int secondaryTimeoutSeconds) { this.secondaryTimeoutSeconds = secondaryTimeoutSeconds; }
}
