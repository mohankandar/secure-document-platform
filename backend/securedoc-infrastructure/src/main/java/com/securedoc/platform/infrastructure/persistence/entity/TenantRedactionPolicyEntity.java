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

    @Column(name = "mask_ssn_last_four", nullable = false)
    private boolean maskSsnLastFour;

    /** Migration 048 (task 0047): "Searchable redacted pages", on by default. */
    @Column(name = "searchable_redacted_pages", nullable = false)
    private boolean searchableRedactedPages = true;

    /** Migration 050 (task 0049): page batches of large documents. */
    @Column(name = "page_batch_size", nullable = false)
    private int pageBatchSize = 20;

    @Column(name = "page_batch_overlap", nullable = false)
    private int pageBatchOverlap = 2;

    /** Migration 080 (task 0064): vision on/off and the routing threshold. */
    @Column(name = "vision_enabled", nullable = false)
    private boolean visionEnabled = true;

    @Column(name = "vision_ocr_threshold", nullable = false)
    private double visionOcrThreshold = 0.85;

    /** Migration 084 (task 0083): a second AI reads the finished file. */
    @Column(name = "second_check_enabled", nullable = false)
    private boolean secondCheckEnabled;

    /** Migration 085 (task 0065): a document summary and case instructions for the names search. */
    @Column(name = "document_context_enabled", nullable = false)
    private boolean documentContextEnabled;

    /** Migration 070 (task 0062): off only for an entity from before the catalog, until its policy is saved. */
    @Column(name = "locked_items_on", nullable = false)
    private boolean lockedItemsOn = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isVisionEnabled() { return visionEnabled; }
    public void setVisionEnabled(boolean visionEnabled) { this.visionEnabled = visionEnabled; }
    public double getVisionOcrThreshold() { return visionOcrThreshold; }
    public void setVisionOcrThreshold(double visionOcrThreshold) { this.visionOcrThreshold = visionOcrThreshold; }
    public boolean isSecondCheckEnabled() { return secondCheckEnabled; }
    public void setSecondCheckEnabled(boolean secondCheckEnabled) { this.secondCheckEnabled = secondCheckEnabled; }
    public boolean isDocumentContextEnabled() { return documentContextEnabled; }
    public void setDocumentContextEnabled(boolean enabled) { this.documentContextEnabled = enabled; }
    public boolean isLockedItemsOn() { return lockedItemsOn; }
    public void setLockedItemsOn(boolean lockedItemsOn) { this.lockedItemsOn = lockedItemsOn; }
    public int getPageBatchSize() { return pageBatchSize; }
    public void setPageBatchSize(int pageBatchSize) { this.pageBatchSize = pageBatchSize; }
    public int getPageBatchOverlap() { return pageBatchOverlap; }
    public void setPageBatchOverlap(int pageBatchOverlap) { this.pageBatchOverlap = pageBatchOverlap; }
    public boolean isSearchableRedactedPages() { return searchableRedactedPages; }
    public void setSearchableRedactedPages(boolean searchableRedactedPages) { this.searchableRedactedPages = searchableRedactedPages; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public double getAutoRedactThreshold() { return autoRedactThreshold; }
    public void setAutoRedactThreshold(double autoRedactThreshold) { this.autoRedactThreshold = autoRedactThreshold; }
    public String getReviewMode() { return reviewMode; }
    public void setReviewMode(String reviewMode) { this.reviewMode = reviewMode; }
    public boolean isMaskSsnLastFour() { return maskSsnLastFour; }
    public void setMaskSsnLastFour(boolean maskSsnLastFour) { this.maskSsnLastFour = maskSsnLastFour; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
