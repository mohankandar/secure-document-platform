package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "findings")
public class FindingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "detector_name", nullable = false)
    private String detectorName;

    @Column(name = "detector_version")
    private String detectorVersion;

    @Column(name = "snippet")
    private String snippet;

    @Column(name = "bbox_left")
    private Double bboxLeft;

    @Column(name = "bbox_top")
    private Double bboxTop;

    @Column(name = "bbox_width")
    private Double bboxWidth;

    @Column(name = "bbox_height")
    private Double bboxHeight;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public FindingEntity() {
    }

    // --- Getters/Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }

    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int startOffset) { this.startOffset = startOffset; }

    public int getEndOffset() { return endOffset; }
    public void setEndOffset(int endOffset) { this.endOffset = endOffset; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getDetectorName() { return detectorName; }
    public void setDetectorName(String detectorName) { this.detectorName = detectorName; }

    public String getDetectorVersion() { return detectorVersion; }
    public void setDetectorVersion(String detectorVersion) { this.detectorVersion = detectorVersion; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public Double getBboxLeft() { return bboxLeft; }
    public void setBboxLeft(Double bboxLeft) { this.bboxLeft = bboxLeft; }

    public Double getBboxTop() { return bboxTop; }
    public void setBboxTop(Double bboxTop) { this.bboxTop = bboxTop; }

    public Double getBboxWidth() { return bboxWidth; }
    public void setBboxWidth(Double bboxWidth) { this.bboxWidth = bboxWidth; }

    public Double getBboxHeight() { return bboxHeight; }
    public void setBboxHeight(Double bboxHeight) { this.bboxHeight = bboxHeight; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
