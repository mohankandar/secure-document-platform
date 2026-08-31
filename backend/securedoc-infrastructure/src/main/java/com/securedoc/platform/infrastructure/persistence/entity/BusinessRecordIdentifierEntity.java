package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="business_record_identifiers")
public class BusinessRecordIdentifierEntity {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="business_record_id", nullable=false) private UUID businessRecordId;
    @Column(name="definition_id", nullable=false) private UUID definitionId;
    @Column(name="identifier_value", nullable=false) private String identifierValue;
    @Column(name="normalized_value", nullable=false) private String normalizedValue;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public UUID getBusinessRecordId(){return businessRecordId;} public void setBusinessRecordId(UUID v){businessRecordId=v;}
    public UUID getDefinitionId(){return definitionId;} public void setDefinitionId(UUID v){definitionId=v;}
    public String getIdentifierValue(){return identifierValue;} public void setIdentifierValue(String v){identifierValue=v;}
    public String getNormalizedValue(){return normalizedValue;} public void setNormalizedValue(String v){normalizedValue=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}
