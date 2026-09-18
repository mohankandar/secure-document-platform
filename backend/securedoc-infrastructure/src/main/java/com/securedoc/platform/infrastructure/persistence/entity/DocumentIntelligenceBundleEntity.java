package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "document_intelligence_bundles")
public class DocumentIntelligenceBundleEntity {
    @Id
    @Column(name = "version", nullable = false, length = 80)
    private String version;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "model_version", nullable = false, length = 120)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false, length = 120)
    private String promptVersion;

    @Column(name = "detector_version", nullable = false, length = 120)
    private String detectorVersion;

    @Column(name = "ocr_rules_version", nullable = false, length = 120)
    private String ocrRulesVersion;

    @Column(name = "profile_version", nullable = false, length = 120)
    private String profileVersion;

    @Column(name = "validation_rules_version", nullable = false, length = 120)
    private String validationRulesVersion;

    @Column(name = "thresholds_version", nullable = false, length = 120)
    private String thresholdsVersion;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getDetectorVersion() { return detectorVersion; }
    public void setDetectorVersion(String detectorVersion) { this.detectorVersion = detectorVersion; }
    public String getOcrRulesVersion() { return ocrRulesVersion; }
    public void setOcrRulesVersion(String ocrRulesVersion) { this.ocrRulesVersion = ocrRulesVersion; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String profileVersion) { this.profileVersion = profileVersion; }
    public String getValidationRulesVersion() { return validationRulesVersion; }
    public void setValidationRulesVersion(String validationRulesVersion) { this.validationRulesVersion = validationRulesVersion; }
    public String getThresholdsVersion() { return thresholdsVersion; }
    public void setThresholdsVersion(String thresholdsVersion) { this.thresholdsVersion = thresholdsVersion; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant promotedAt) { this.promotedAt = promotedAt; }
}
