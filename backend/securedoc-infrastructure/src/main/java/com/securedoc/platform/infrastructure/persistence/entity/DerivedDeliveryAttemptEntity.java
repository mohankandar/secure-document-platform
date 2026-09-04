package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="derived_delivery_attempts",uniqueConstraints=@UniqueConstraint(name="uk_derived_delivery_attempt",columnNames={"derived_delivery_id","attempt_number"}))
public class DerivedDeliveryAttemptEntity {
    @Id private UUID id; @Column(name="derived_delivery_id",nullable=false) private UUID derivedDeliveryId; @Column(name="attempt_number",nullable=false) private int attemptNumber; @Column(name="connection_id",nullable=false) private UUID connectionId; @Column(name="connection_config_version",nullable=false) private int connectionConfigurationVersion;
    @Column(nullable=false,length=30) private String status; @Column(name="failure_category",length=120) private String failureCategory; @Column(name="started_at",nullable=false) private Instant startedAt; @Column(name="completed_at") private Instant completedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getDerivedDeliveryId(){return derivedDeliveryId;} public void setDerivedDeliveryId(UUID v){derivedDeliveryId=v;} public int getAttemptNumber(){return attemptNumber;} public void setAttemptNumber(int v){attemptNumber=v;} public UUID getConnectionId(){return connectionId;} public void setConnectionId(UUID v){connectionId=v;} public int getConnectionConfigurationVersion(){return connectionConfigurationVersion;} public void setConnectionConfigurationVersion(int v){connectionConfigurationVersion=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getFailureCategory(){return failureCategory;} public void setFailureCategory(String v){failureCategory=v;} public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;} public Instant getCompletedAt(){return completedAt;} public void setCompletedAt(Instant v){completedAt=v;}
}
