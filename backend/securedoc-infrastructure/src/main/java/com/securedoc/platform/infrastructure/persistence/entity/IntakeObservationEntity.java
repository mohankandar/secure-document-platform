package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="intake_observations")
public class IntakeObservationEntity {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="source_reference", nullable=false) private String sourceReference;
    @Column(nullable=false) private String status;
    @Column(name="reason_code") private String reasonCode;
    private String fingerprint;
    @Column(name="package_id") private UUID packageId;
    @Column(name="first_seen_at", nullable=false) private Instant firstSeenAt;
    @Column(name="visible_after", nullable=false) private Instant visibleAfter;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getSourceReference(){return sourceReference;} public void setSourceReference(String v){sourceReference=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getReasonCode(){return reasonCode;} public void setReasonCode(String v){reasonCode=v;}
    public String getFingerprint(){return fingerprint;} public void setFingerprint(String v){fingerprint=v;}
    public UUID getPackageId(){return packageId;} public void setPackageId(UUID v){packageId=v;}
    public Instant getFirstSeenAt(){return firstSeenAt;} public void setFirstSeenAt(Instant v){firstSeenAt=v;}
    public Instant getVisibleAfter(){return visibleAfter;} public void setVisibleAfter(Instant v){visibleAfter=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
