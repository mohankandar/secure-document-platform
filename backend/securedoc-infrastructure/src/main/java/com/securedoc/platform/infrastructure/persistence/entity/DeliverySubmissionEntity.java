package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_submissions", uniqueConstraints = @UniqueConstraint(name="uk_delivery_submission_package_cycle", columnNames={"tenant_id","package_id","export_cycle"}))
public class DeliverySubmissionEntity {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="package_id", nullable=false) private UUID packageId;
    @Column(name="export_cycle", nullable=false) private int exportCycle;
    @Column(name="source_submission_id", nullable=false, length=120) private String sourceSubmissionId;
    @Column(name="destination_type", nullable=false, length=40) private String destinationType;
    @Column(nullable=false, length=40) private String status;
    @Column(name="attempt_count", nullable=false) private int attemptCount;
    @Column(name="destination_submission_reference", length=255) private String destinationSubmissionReference;
    @Column(name="last_failure_category", length=120) private String lastFailureCategory;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Column(name="completed_at") private Instant completedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public UUID getPackageId(){return packageId;} public void setPackageId(UUID v){packageId=v;}
    public int getExportCycle(){return exportCycle;} public void setExportCycle(int v){exportCycle=v;}
    public String getSourceSubmissionId(){return sourceSubmissionId;} public void setSourceSubmissionId(String v){sourceSubmissionId=v;}
    public String getDestinationType(){return destinationType;} public void setDestinationType(String v){destinationType=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getAttemptCount(){return attemptCount;} public void setAttemptCount(int v){attemptCount=v;}
    public String getDestinationSubmissionReference(){return destinationSubmissionReference;} public void setDestinationSubmissionReference(String v){destinationSubmissionReference=v;}
    public String getLastFailureCategory(){return lastFailureCategory;} public void setLastFailureCategory(String v){lastFailureCategory=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
    public Instant getCompletedAt(){return completedAt;} public void setCompletedAt(Instant v){completedAt=v;}
}
