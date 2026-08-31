package com.securedoc.platform.infrastructure.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local/on-prem OCR settings for the Tesseract CLI adapter. */
@ConfigurationProperties(prefix = "securedoc.ocr.tesseract")
public class TesseractOcrProperties {

    private String executable = "tesseract";
    private String language = "eng";
    private int pageSegmentationMode = 3;
    private int timeoutSeconds = 60;
    private int renderDpi = 220;
    private int minNativeCharacters = 32;
    private double minimumPageConfidence = 0.55;
    private double blankPageDarkPixelRatio = 0.0015;

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public int getPageSegmentationMode() {
        return pageSegmentationMode;
    }

    public void setPageSegmentationMode(int pageSegmentationMode) {
        this.pageSegmentationMode = pageSegmentationMode;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getRenderDpi() {
        return renderDpi;
    }

    public void setRenderDpi(int renderDpi) {
        this.renderDpi = renderDpi;
    }

    public int getMinNativeCharacters() {
        return minNativeCharacters;
    }

    public void setMinNativeCharacters(int minNativeCharacters) {
        this.minNativeCharacters = minNativeCharacters;
    }

    public double getMinimumPageConfidence() {
        return minimumPageConfidence;
    }

    public void setMinimumPageConfidence(double minimumPageConfidence) {
        this.minimumPageConfidence = minimumPageConfidence;
    }

    public double getBlankPageDarkPixelRatio() {
        return blankPageDarkPixelRatio;
    }

    public void setBlankPageDarkPixelRatio(double blankPageDarkPixelRatio) {
        this.blankPageDarkPixelRatio = blankPageDarkPixelRatio;
    }
}
