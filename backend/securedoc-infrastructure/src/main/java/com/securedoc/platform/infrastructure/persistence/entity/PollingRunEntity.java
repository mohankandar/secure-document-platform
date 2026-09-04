package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "polling_runs")
public class PollingRunEntity {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "integration_connection_id", nullable = false) private UUID integrationConnectionId;
    @Column(name = "trigger_type", nullable = false, length = 32) private String triggerType;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "active_lock_key", length = 80, unique = true) private String activeLockKey;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "packages_discovered", nullable = false) private int packagesDiscovered;
    @Column(name = "documents_expected", nullable = false) private int documentsExpected;
    @Column(name = "documents_retrieved", nullable = false) private int documentsRetrieved;
    @Column(name = "packages_acknowledged", nullable = false) private int packagesAcknowledged;
    @Column(name = "failure_category", length = 120) private String failureCategory;
    @Column(name = "failure_detail", length = 500) private String failureDetail;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public UUID getIntegrationConnectionId(){return integrationConnectionId;} public void setIntegrationConnectionId(UUID v){integrationConnectionId=v;}
    public String getTriggerType(){return triggerType;} public void setTriggerType(String v){triggerType=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getActiveLockKey(){return activeLockKey;} public void setActiveLockKey(String v){activeLockKey=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;}
    public Instant getCompletedAt(){return completedAt;} public void setCompletedAt(Instant v){completedAt=v;}
    public int getPackagesDiscovered(){return packagesDiscovered;} public void setPackagesDiscovered(int v){packagesDiscovered=v;}
    public int getDocumentsExpected(){return documentsExpected;} public void setDocumentsExpected(int v){documentsExpected=v;}
    public int getDocumentsRetrieved(){return documentsRetrieved;} public void setDocumentsRetrieved(int v){documentsRetrieved=v;}
    public int getPackagesAcknowledged(){return packagesAcknowledged;} public void setPackagesAcknowledged(int v){packagesAcknowledged=v;}
    public String getFailureCategory(){return failureCategory;} public void setFailureCategory(String v){failureCategory=v;}
    public String getFailureDetail(){return failureDetail;} public void setFailureDetail(String v){failureDetail=v;}
}
