package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "processing_incidents")
public class ProcessingIncidentEntity {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "package_id", nullable = false) private UUID packageId;
    @Column(name = "document_id") private UUID documentId;
    @Column(name = "stage", nullable = false, length = 64) private String stage;
    @Column(name = "component", nullable = false, length = 64) private String component;
    @Column(name = "failure_category", nullable = false, length = 96) private String failureCategory;
    @Column(name = "reason_code", nullable = false, length = 96) private String reasonCode;
    @Column(name = "retryable", nullable = false) private boolean retryable;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "correlation_id", nullable = false) private UUID correlationId;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "business_continuity_used", nullable = false) private boolean businessContinuityUsed;
    @Column(name = "business_outcome", length = 32) private String businessOutcome;
    @Column(name = "safe_summary", nullable = false, length = 500) private String safeSummary;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "processing_fingerprint", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> processingFingerprint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "safe_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> safeMetadata;
    @Column(name = "first_observed_at", nullable = false) private Instant firstObservedAt;
    @Column(name = "last_observed_at", nullable = false) private Instant lastObservedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public UUID getPackageId(){return packageId;} public void setPackageId(UUID v){packageId=v;}
    public UUID getDocumentId(){return documentId;} public void setDocumentId(UUID v){documentId=v;}
    public String getStage(){return stage;} public void setStage(String v){stage=v;}
    public String getComponent(){return component;} public void setComponent(String v){component=v;}
    public String getFailureCategory(){return failureCategory;} public void setFailureCategory(String v){failureCategory=v;}
    public String getReasonCode(){return reasonCode;} public void setReasonCode(String v){reasonCode=v;}
    public boolean isRetryable(){return retryable;} public void setRetryable(boolean v){retryable=v;}
    public int getAttemptCount(){return attemptCount;} public void setAttemptCount(int v){attemptCount=v;}
    public UUID getCorrelationId(){return correlationId;} public void setCorrelationId(UUID v){correlationId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public boolean isBusinessContinuityUsed(){return businessContinuityUsed;} public void setBusinessContinuityUsed(boolean v){businessContinuityUsed=v;}
    public String getBusinessOutcome(){return businessOutcome;} public void setBusinessOutcome(String v){businessOutcome=v;}
    public String getSafeSummary(){return safeSummary;} public void setSafeSummary(String v){safeSummary=v;}
    public Map<String,String> getProcessingFingerprint(){return processingFingerprint;} public void setProcessingFingerprint(Map<String,String> v){processingFingerprint=v;}
    public Map<String,String> getSafeMetadata(){return safeMetadata;} public void setSafeMetadata(Map<String,String> v){safeMetadata=v;}
    public Instant getFirstObservedAt(){return firstObservedAt;} public void setFirstObservedAt(Instant v){firstObservedAt=v;}
    public Instant getLastObservedAt(){return lastObservedAt;} public void setLastObservedAt(Instant v){lastObservedAt=v;}
    public Instant getResolvedAt(){return resolvedAt;} public void setResolvedAt(Instant v){resolvedAt=v;}
}
