package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_redaction_policies")
public class TenantRedactionPolicyEntity {
    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "auto_redact_threshold", nullable = false)
    private double autoRedactThreshold;

    @Column(name = "review_mode", nullable = false)
    private String reviewMode;

    @Column(name = "ssn_enabled", nullable = false)
    private boolean ssnEnabled;

    @Column(name = "mask_ssn_last_four", nullable = false)
    private boolean maskSsnLastFour;

    @Column(name = "date_of_birth_enabled", nullable = false)
    private boolean dateOfBirthEnabled;

    @Column(name = "credit_card_number_enabled", nullable = false)
    private boolean creditCardNumberEnabled;

    @Column(name = "credit_score_enabled", nullable = false)
    private boolean creditScoreEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public double getAutoRedactThreshold() { return autoRedactThreshold; }
    public void setAutoRedactThreshold(double autoRedactThreshold) { this.autoRedactThreshold = autoRedactThreshold; }
    public String getReviewMode() { return reviewMode; }
    public void setReviewMode(String reviewMode) { this.reviewMode = reviewMode; }
    public boolean isSsnEnabled() { return ssnEnabled; }
    public void setSsnEnabled(boolean ssnEnabled) { this.ssnEnabled = ssnEnabled; }
    public boolean isMaskSsnLastFour() { return maskSsnLastFour; }
    public void setMaskSsnLastFour(boolean maskSsnLastFour) { this.maskSsnLastFour = maskSsnLastFour; }
    public boolean isDateOfBirthEnabled() { return dateOfBirthEnabled; }
    public void setDateOfBirthEnabled(boolean dateOfBirthEnabled) { this.dateOfBirthEnabled = dateOfBirthEnabled; }
    public boolean isCreditCardNumberEnabled() { return creditCardNumberEnabled; }
    public void setCreditCardNumberEnabled(boolean creditCardNumberEnabled) { this.creditCardNumberEnabled = creditCardNumberEnabled; }
    public boolean isCreditScoreEnabled() { return creditScoreEnabled; }
    public void setCreditScoreEnabled(boolean creditScoreEnabled) { this.creditScoreEnabled = creditScoreEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
