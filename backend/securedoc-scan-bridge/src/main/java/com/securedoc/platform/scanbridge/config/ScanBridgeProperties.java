package com.securedoc.platform.scanbridge.config;

import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "securedoc.scan-bridge")
public class ScanBridgeProperties {
    private boolean enabled = true;
    private String tenantId = "00000000-0000-0000-0000-000000000001";
    private String bridgeId = "BRIDGE-LOCAL-01";
    private String siteId = "HOME-LAB";
    private String scannerId = "SCANNER-SIM-01";
    private ScannerCaptureMode mode = ScannerCaptureMode.SIMULATOR;
    private String lexavaultBaseUrl = "http://localhost:8080/api/v1";
    private String clientPrincipal = "scanner-bridge-local";
    private String localActorId = "00000000-0000-0000-0000-000000000099";
    private String authorizationBearerToken;
    private String workPath = "./scanner-bridge/work";
    private String problemPath = "./scanner-bridge/problem";
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
    private Simulator simulator = new Simulator();
    private Physical physical = new Physical();
    private Storage storage = new Storage();
    private Delivery delivery = new Delivery();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getBridgeId() { return bridgeId; }
    public void setBridgeId(String bridgeId) { this.bridgeId = bridgeId; }
    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }
    public String getScannerId() { return scannerId; }
    public void setScannerId(String scannerId) { this.scannerId = scannerId; }
    public ScannerCaptureMode getMode() { return mode; }
    public void setMode(ScannerCaptureMode mode) { this.mode = mode == null ? ScannerCaptureMode.SIMULATOR : mode; }
    public String getLexavaultBaseUrl() { return lexavaultBaseUrl; }
    public void setLexavaultBaseUrl(String lexavaultBaseUrl) { this.lexavaultBaseUrl = lexavaultBaseUrl; }
    public String getClientPrincipal() { return clientPrincipal; }
    public void setClientPrincipal(String clientPrincipal) { this.clientPrincipal = clientPrincipal; }
    public String getLocalActorId() { return localActorId; }
    public void setLocalActorId(String localActorId) { this.localActorId = localActorId; }
    public String getAuthorizationBearerToken() { return authorizationBearerToken; }
    public void setAuthorizationBearerToken(String authorizationBearerToken) { this.authorizationBearerToken = authorizationBearerToken; }
    public String getWorkPath() { return workPath; }
    public void setWorkPath(String workPath) { this.workPath = workPath; }
    public String getProblemPath() { return problemPath; }
    public void setProblemPath(String problemPath) { this.problemPath = problemPath; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins); }
    public Simulator getSimulator() { return simulator; }
    public void setSimulator(Simulator simulator) { this.simulator = simulator == null ? new Simulator() : simulator; }
    public Physical getPhysical() { return physical; }
    public void setPhysical(Physical physical) { this.physical = physical == null ? new Physical() : physical; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage == null ? new Storage() : storage; }
    public Delivery getDelivery() { return delivery; }
    public void setDelivery(Delivery delivery) { this.delivery = delivery == null ? new Delivery() : delivery; }

    public static class Simulator {
        private String inboxPath = "./scanner-bridge/inbox";
        private long scanIntervalMs = 1000;
        private long stableWindowMs = 1500;

        public String getInboxPath() { return inboxPath; }
        public void setInboxPath(String inboxPath) { this.inboxPath = inboxPath; }
        public long getScanIntervalMs() { return scanIntervalMs; }
        public void setScanIntervalMs(long scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
        public long getStableWindowMs() { return stableWindowMs; }
        public void setStableWindowMs(long stableWindowMs) { this.stableWindowMs = stableWindowMs; }
    }


    public static class Physical {
        private String provider = "WIA";
        private String deviceId;
        private String deviceName;
        private String source = "AUTO";
        private boolean duplex;
        private int dpi = 300;
        private int maxPages = 250;
        private int acquisitionTimeoutSeconds = 900;
        private long availabilityCacheMs = 10000;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider == null ? "WIA" : provider; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public String getDeviceName() { return deviceName; }
        public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source == null ? "AUTO" : source; }
        public boolean isDuplex() { return duplex; }
        public void setDuplex(boolean duplex) { this.duplex = duplex; }
        public int getDpi() { return dpi; }
        public void setDpi(int dpi) { this.dpi = dpi; }
        public int getMaxPages() { return maxPages; }
        public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
        public int getAcquisitionTimeoutSeconds() { return acquisitionTimeoutSeconds; }
        public void setAcquisitionTimeoutSeconds(int acquisitionTimeoutSeconds) { this.acquisitionTimeoutSeconds = acquisitionTimeoutSeconds; }
        public long getAvailabilityCacheMs() { return availabilityCacheMs; }
        public void setAvailabilityCacheMs(long availabilityCacheMs) { this.availabilityCacheMs = availabilityCacheMs; }
    }


    public static class Storage {
        private long maxScanPdfSizeBytes = 2L * 1024L * 1024L * 1024L;
        private long minimumUsableSpaceBytes = 5L * 1024L * 1024L * 1024L;

        public long getMaxScanPdfSizeBytes() { return maxScanPdfSizeBytes; }
        public void setMaxScanPdfSizeBytes(long maxScanPdfSizeBytes) { this.maxScanPdfSizeBytes = maxScanPdfSizeBytes; }
        public long getMinimumUsableSpaceBytes() { return minimumUsableSpaceBytes; }
        public void setMinimumUsableSpaceBytes(long minimumUsableSpaceBytes) { this.minimumUsableSpaceBytes = minimumUsableSpaceBytes; }
    }

    public static class Delivery {
        private long pollIntervalMs = 1000;
        private long retryInitialDelayMs = 2000;
        private long retryMaxDelayMs = 60000;
        private int requestTimeoutSeconds = 600;
        private boolean cleanupAcceptedPdf = true;
        private long terminalStateRetentionHours = 72;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public long getRetryInitialDelayMs() { return retryInitialDelayMs; }
        public void setRetryInitialDelayMs(long retryInitialDelayMs) { this.retryInitialDelayMs = retryInitialDelayMs; }
        public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
        public void setRetryMaxDelayMs(long retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
        public boolean isCleanupAcceptedPdf() { return cleanupAcceptedPdf; }
        public void setCleanupAcceptedPdf(boolean cleanupAcceptedPdf) { this.cleanupAcceptedPdf = cleanupAcceptedPdf; }
        public long getTerminalStateRetentionHours() { return terminalStateRetentionHours; }
        public void setTerminalStateRetentionHours(long terminalStateRetentionHours) { this.terminalStateRetentionHours = terminalStateRetentionHours; }
    }
}
