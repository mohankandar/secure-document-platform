package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "processing_incident_events")
public class ProcessingIncidentEventEntity {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "event_type", nullable = false, length = 48) private String eventType;
    @Column(name = "attempt_number") private Integer attemptNumber;
    @Column(name = "scope", length = 64) private String scope;
    @Column(name = "reason_code", length = 96) private String reasonCode;
    @Column(name = "retryable") private Boolean retryable;
    @Column(name = "correlation_id", nullable = false) private UUID correlationId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "safe_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> safeMetadata;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getIncidentId(){return incidentId;} public void setIncidentId(UUID v){incidentId=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;}
    public Integer getAttemptNumber(){return attemptNumber;} public void setAttemptNumber(Integer v){attemptNumber=v;}
    public String getScope(){return scope;} public void setScope(String v){scope=v;}
    public String getReasonCode(){return reasonCode;} public void setReasonCode(String v){reasonCode=v;}
    public Boolean getRetryable(){return retryable;} public void setRetryable(Boolean v){retryable=v;}
    public UUID getCorrelationId(){return correlationId;} public void setCorrelationId(UUID v){correlationId=v;}
    public Map<String,String> getSafeMetadata(){return safeMetadata;} public void setSafeMetadata(Map<String,String> v){safeMetadata=v;}
    public Instant getOccurredAt(){return occurredAt;} public void setOccurredAt(Instant v){occurredAt=v;}
}
