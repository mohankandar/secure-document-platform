package com.securedoc.platform.api.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment-managed runtime selection for provider-neutral sensitive-content analysis.
 *
 * <p>The top-level fields define the default AI profile. Optional tenantProfiles override
 * provider/model/endpoint per tenant without changing business redaction policy. API keys are
 * deployment secrets (typically environment-variable substitutions); they are never exposed by
 * Business Admin configuration or logs.
 */
public class AiRuntimeProperties {
    private String provider = "ollama";
    private String baseUrl = "http://localhost:11434";
    private String model = "qwen2.5:7b";
    private String apiKey = "";
    private int timeoutSeconds = 60;
    private int parallelism = 2;
    private int pageMaxAttempts = 2;
    private long pageRetryBackoffMs = 500;
    // Requirement 5 local-inference tuning. Zero token limits mean "provider/model default".
    private String ollamaKeepAlive = "5m";
    private boolean ollamaStructuredOutput = true;
    private boolean ollamaThinkingEnabled = false;
    private int ollamaContextWindowTokens = 0;
    private int ollamaMaxOutputTokens = 0;
    private int ollamaPageBatchSize = 1;
    private int ollamaPageBatchMaxCharacters = 14000;
    // Optional startup preload for the deployment default Ollama model. Tenant-specific models remain lazy.
    private boolean ollamaPreloadEnabled = false;
    private boolean ollamaPreloadFailOnError = false;
    private int ollamaPreloadTimeoutSeconds = 300;
    private boolean requireTenantProfile = false;

    private boolean secondaryEnabled = false;
    private String secondaryProvider = "ollama";
    private String secondaryBaseUrl = "http://localhost:11434";
    private String secondaryModel = "";
    private String secondaryApiKey = "";
    private int secondaryTimeoutSeconds = 60;

    private Map<String, TenantProfile> tenantProfiles = new LinkedHashMap<>();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getParallelism() { return parallelism; }
    public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    public int getPageMaxAttempts() { return pageMaxAttempts; }
    public void setPageMaxAttempts(int pageMaxAttempts) { this.pageMaxAttempts = pageMaxAttempts; }
    public long getPageRetryBackoffMs() { return pageRetryBackoffMs; }
    public void setPageRetryBackoffMs(long pageRetryBackoffMs) { this.pageRetryBackoffMs = pageRetryBackoffMs; }
    public String getOllamaKeepAlive() { return ollamaKeepAlive; }
    public void setOllamaKeepAlive(String ollamaKeepAlive) { this.ollamaKeepAlive = ollamaKeepAlive; }
    public boolean isOllamaStructuredOutput() { return ollamaStructuredOutput; }
    public void setOllamaStructuredOutput(boolean ollamaStructuredOutput) { this.ollamaStructuredOutput = ollamaStructuredOutput; }
    public boolean isOllamaThinkingEnabled() { return ollamaThinkingEnabled; }
    public void setOllamaThinkingEnabled(boolean ollamaThinkingEnabled) { this.ollamaThinkingEnabled = ollamaThinkingEnabled; }
    public int getOllamaContextWindowTokens() { return ollamaContextWindowTokens; }
    public void setOllamaContextWindowTokens(int ollamaContextWindowTokens) { this.ollamaContextWindowTokens = ollamaContextWindowTokens; }
    public int getOllamaMaxOutputTokens() { return ollamaMaxOutputTokens; }
    public void setOllamaMaxOutputTokens(int ollamaMaxOutputTokens) { this.ollamaMaxOutputTokens = ollamaMaxOutputTokens; }
    public int getOllamaPageBatchSize() { return ollamaPageBatchSize; }
    public void setOllamaPageBatchSize(int ollamaPageBatchSize) { this.ollamaPageBatchSize = ollamaPageBatchSize; }
    public int getOllamaPageBatchMaxCharacters() { return ollamaPageBatchMaxCharacters; }
    public void setOllamaPageBatchMaxCharacters(int ollamaPageBatchMaxCharacters) { this.ollamaPageBatchMaxCharacters = ollamaPageBatchMaxCharacters; }
    public boolean isOllamaPreloadEnabled() { return ollamaPreloadEnabled; }
    public void setOllamaPreloadEnabled(boolean ollamaPreloadEnabled) { this.ollamaPreloadEnabled = ollamaPreloadEnabled; }
    public boolean isOllamaPreloadFailOnError() { return ollamaPreloadFailOnError; }
    public void setOllamaPreloadFailOnError(boolean ollamaPreloadFailOnError) { this.ollamaPreloadFailOnError = ollamaPreloadFailOnError; }
    public int getOllamaPreloadTimeoutSeconds() { return ollamaPreloadTimeoutSeconds; }
    public void setOllamaPreloadTimeoutSeconds(int ollamaPreloadTimeoutSeconds) { this.ollamaPreloadTimeoutSeconds = ollamaPreloadTimeoutSeconds; }
    public boolean isRequireTenantProfile() { return requireTenantProfile; }
    public void setRequireTenantProfile(boolean requireTenantProfile) { this.requireTenantProfile = requireTenantProfile; }

    public boolean isSecondaryEnabled() { return secondaryEnabled; }
    public void setSecondaryEnabled(boolean secondaryEnabled) { this.secondaryEnabled = secondaryEnabled; }
    public String getSecondaryProvider() { return secondaryProvider; }
    public void setSecondaryProvider(String secondaryProvider) { this.secondaryProvider = secondaryProvider; }
    public String getSecondaryBaseUrl() { return secondaryBaseUrl; }
    public void setSecondaryBaseUrl(String secondaryBaseUrl) { this.secondaryBaseUrl = secondaryBaseUrl; }
    public String getSecondaryModel() { return secondaryModel; }
    public void setSecondaryModel(String secondaryModel) { this.secondaryModel = secondaryModel; }
    public String getSecondaryApiKey() { return secondaryApiKey; }
    public void setSecondaryApiKey(String secondaryApiKey) { this.secondaryApiKey = secondaryApiKey; }
    public int getSecondaryTimeoutSeconds() { return secondaryTimeoutSeconds; }
    public void setSecondaryTimeoutSeconds(int secondaryTimeoutSeconds) { this.secondaryTimeoutSeconds = secondaryTimeoutSeconds; }

    public Map<String, TenantProfile> getTenantProfiles() { return tenantProfiles; }
    public void setTenantProfiles(Map<String, TenantProfile> tenantProfiles) {
        this.tenantProfiles = tenantProfiles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tenantProfiles);
    }

    /** Optional deployment-managed per-tenant AI profile. No browser-managed secret storage. */
    public static class TenantProfile {
        private String provider;
        private String baseUrl;
        private String model;
        private String apiKey = "";
        private int timeoutSeconds;
        private int parallelism;
        private int pageMaxAttempts;
        private long pageRetryBackoffMs = -1L;
        private String ollamaKeepAlive;
        private Boolean ollamaStructuredOutput;
        private Boolean ollamaThinkingEnabled;
        private int ollamaContextWindowTokens = -1;
        private int ollamaMaxOutputTokens = -1;
        private int ollamaPageBatchSize;
        private int ollamaPageBatchMaxCharacters;

        private boolean secondaryEnabled;
        private String secondaryProvider;
        private String secondaryBaseUrl;
        private String secondaryModel;
        private String secondaryApiKey = "";
        private int secondaryTimeoutSeconds;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getParallelism() { return parallelism; }
        public void setParallelism(int parallelism) { this.parallelism = parallelism; }
        public int getPageMaxAttempts() { return pageMaxAttempts; }
        public void setPageMaxAttempts(int pageMaxAttempts) { this.pageMaxAttempts = pageMaxAttempts; }
        public long getPageRetryBackoffMs() { return pageRetryBackoffMs; }
        public void setPageRetryBackoffMs(long pageRetryBackoffMs) { this.pageRetryBackoffMs = pageRetryBackoffMs; }
        public String getOllamaKeepAlive() { return ollamaKeepAlive; }
        public void setOllamaKeepAlive(String ollamaKeepAlive) { this.ollamaKeepAlive = ollamaKeepAlive; }
        public Boolean getOllamaStructuredOutput() { return ollamaStructuredOutput; }
        public void setOllamaStructuredOutput(Boolean ollamaStructuredOutput) { this.ollamaStructuredOutput = ollamaStructuredOutput; }
        public Boolean getOllamaThinkingEnabled() { return ollamaThinkingEnabled; }
        public void setOllamaThinkingEnabled(Boolean ollamaThinkingEnabled) { this.ollamaThinkingEnabled = ollamaThinkingEnabled; }
        public int getOllamaContextWindowTokens() { return ollamaContextWindowTokens; }
        public void setOllamaContextWindowTokens(int ollamaContextWindowTokens) { this.ollamaContextWindowTokens = ollamaContextWindowTokens; }
        public int getOllamaMaxOutputTokens() { return ollamaMaxOutputTokens; }
        public void setOllamaMaxOutputTokens(int ollamaMaxOutputTokens) { this.ollamaMaxOutputTokens = ollamaMaxOutputTokens; }
        public int getOllamaPageBatchSize() { return ollamaPageBatchSize; }
        public void setOllamaPageBatchSize(int ollamaPageBatchSize) { this.ollamaPageBatchSize = ollamaPageBatchSize; }
        public int getOllamaPageBatchMaxCharacters() { return ollamaPageBatchMaxCharacters; }
        public void setOllamaPageBatchMaxCharacters(int ollamaPageBatchMaxCharacters) { this.ollamaPageBatchMaxCharacters = ollamaPageBatchMaxCharacters; }
        public boolean isSecondaryEnabled() { return secondaryEnabled; }
        public void setSecondaryEnabled(boolean secondaryEnabled) { this.secondaryEnabled = secondaryEnabled; }
        public String getSecondaryProvider() { return secondaryProvider; }
        public void setSecondaryProvider(String secondaryProvider) { this.secondaryProvider = secondaryProvider; }
        public String getSecondaryBaseUrl() { return secondaryBaseUrl; }
        public void setSecondaryBaseUrl(String secondaryBaseUrl) { this.secondaryBaseUrl = secondaryBaseUrl; }
        public String getSecondaryModel() { return secondaryModel; }
        public void setSecondaryModel(String secondaryModel) { this.secondaryModel = secondaryModel; }
        public String getSecondaryApiKey() { return secondaryApiKey; }
        public void setSecondaryApiKey(String secondaryApiKey) { this.secondaryApiKey = secondaryApiKey; }
        public int getSecondaryTimeoutSeconds() { return secondaryTimeoutSeconds; }
        public void setSecondaryTimeoutSeconds(int secondaryTimeoutSeconds) { this.secondaryTimeoutSeconds = secondaryTimeoutSeconds; }
    }
}
