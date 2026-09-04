package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="delivery_attempts")
public class DeliveryAttemptEntity {
    @Id private UUID id;
    @Column(name="submission_id", nullable=false) private UUID submissionId;
    @Column(name="attempt_number", nullable=false) private int attemptNumber;
    @Column(name="connection_id") private UUID connectionId;
    @Column(name="connection_configuration_version") private Integer connectionConfigurationVersion;
    @Column(nullable=false, length=40) private String status;
    @Column(name="failure_category", length=120) private String failureCategory;
    @Column(name="started_at", nullable=false) private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getSubmissionId(){return submissionId;} public void setSubmissionId(UUID v){submissionId=v;}
    public int getAttemptNumber(){return attemptNumber;} public void setAttemptNumber(int v){attemptNumber=v;}
    public UUID getConnectionId(){return connectionId;} public void setConnectionId(UUID v){connectionId=v;}
    public Integer getConnectionConfigurationVersion(){return connectionConfigurationVersion;} public void setConnectionConfigurationVersion(Integer v){connectionConfigurationVersion=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getFailureCategory(){return failureCategory;} public void setFailureCategory(String v){failureCategory=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;}
    public Instant getCompletedAt(){return completedAt;} public void setCompletedAt(Instant v){completedAt=v;}
}
