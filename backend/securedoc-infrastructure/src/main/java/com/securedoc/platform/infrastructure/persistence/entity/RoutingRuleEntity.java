package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="routing_rules", uniqueConstraints=@UniqueConstraint(name="uk_routing_rule_marker_region",columnNames={"tenant_id","marker_type","marker_value","scan_region"}))
public class RoutingRuleEntity {
    @Id private UUID id;
    @Column(name="tenant_id",nullable=false) private UUID tenantId;
    @Column(name="destination_label",nullable=false,length=120) private String destinationLabel;
    @Column(name="marker_type",nullable=false,length=30) private String markerType;
    @Column(name="marker_value",nullable=false,length=200) private String markerValue;
    @Column(name="scan_region",nullable=false,length=30) private String scanRegion;
    @Column(name="destination_connection_id",nullable=false) private UUID destinationConnectionId;
    @Column(nullable=false) private boolean enabled;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getDestinationLabel(){return destinationLabel;} public void setDestinationLabel(String v){destinationLabel=v;} public String getMarkerType(){return markerType;} public void setMarkerType(String v){markerType=v;}
    public String getMarkerValue(){return markerValue;} public void setMarkerValue(String v){markerValue=v;} public String getScanRegion(){return scanRegion;} public void setScanRegion(String v){scanRegion=v;}
    public UUID getDestinationConnectionId(){return destinationConnectionId;} public void setDestinationConnectionId(UUID v){destinationConnectionId=v;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;} public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
