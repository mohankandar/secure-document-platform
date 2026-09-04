package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="derived_deliveries",uniqueConstraints={
        @UniqueConstraint(name="uk_derived_delivery_rule_document",columnNames={"tenant_id","package_id","export_cycle","source_document_id","routing_rule_id"}),
        @UniqueConstraint(name="uk_derived_source_id",columnNames={"tenant_id","source_derived_document_id"})})
public class DerivedDeliveryEntity {
    @Id private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="package_id",nullable=false) private UUID packageId; @Column(name="source_document_id",nullable=false) private UUID sourceDocumentId;
    @Column(name="export_cycle",nullable=false) private int exportCycle; @Column(name="routing_rule_id") private UUID routingRuleId; @Column(name="destination_connection_id") private UUID destinationConnectionId; @Column(name="connection_config_version") private Integer connectionConfigurationVersion;
    @Column(name="destination_label",length=120) private String destinationLabel; @Column(name="marker_type",length=30) private String markerType; @Column(name="marker_value",length=200) private String markerValue;
    @Column(name="page_numbers",nullable=false,length=2000) private String pageNumbers; @Column(name="artifact_id") private UUID artifactId; @Column(name="source_derived_document_id",nullable=false,length=120) private String sourceDerivedDocumentId; @Column(name="file_name",length=255) private String fileName;
    @Column(nullable=false,length=40) private String status; @Column(name="attempt_count",nullable=false) private int attemptCount; @Column(name="last_failure_category",length=120) private String lastFailureCategory; @Column(nullable=false) private boolean retryable;
    @Column(name="destination_reference",length=512) private String destinationReference; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt; @Column(name="delivered_at") private Instant deliveredAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;} public UUID getPackageId(){return packageId;} public void setPackageId(UUID v){packageId=v;} public UUID getSourceDocumentId(){return sourceDocumentId;} public void setSourceDocumentId(UUID v){sourceDocumentId=v;}
    public int getExportCycle(){return exportCycle;} public void setExportCycle(int v){exportCycle=v;} public UUID getRoutingRuleId(){return routingRuleId;} public void setRoutingRuleId(UUID v){routingRuleId=v;} public UUID getDestinationConnectionId(){return destinationConnectionId;} public void setDestinationConnectionId(UUID v){destinationConnectionId=v;} public Integer getConnectionConfigurationVersion(){return connectionConfigurationVersion;} public void setConnectionConfigurationVersion(Integer v){connectionConfigurationVersion=v;}
    public String getDestinationLabel(){return destinationLabel;} public void setDestinationLabel(String v){destinationLabel=v;} public String getMarkerType(){return markerType;} public void setMarkerType(String v){markerType=v;} public String getMarkerValue(){return markerValue;} public void setMarkerValue(String v){markerValue=v;}
    public String getPageNumbers(){return pageNumbers;} public void setPageNumbers(String v){pageNumbers=v;} public UUID getArtifactId(){return artifactId;} public void setArtifactId(UUID v){artifactId=v;} public String getSourceDerivedDocumentId(){return sourceDerivedDocumentId;} public void setSourceDerivedDocumentId(String v){sourceDerivedDocumentId=v;} public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public int getAttemptCount(){return attemptCount;} public void setAttemptCount(int v){attemptCount=v;} public String getLastFailureCategory(){return lastFailureCategory;} public void setLastFailureCategory(String v){lastFailureCategory=v;} public boolean isRetryable(){return retryable;} public void setRetryable(boolean v){retryable=v;}
    public String getDestinationReference(){return destinationReference;} public void setDestinationReference(String v){destinationReference=v;} public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;} public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;} public Instant getDeliveredAt(){return deliveredAt;} public void setDeliveredAt(Instant v){deliveredAt=v;}
}
