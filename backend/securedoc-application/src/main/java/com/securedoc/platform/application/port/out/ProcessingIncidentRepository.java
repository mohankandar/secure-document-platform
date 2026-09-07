package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import com.securedoc.platform.domain.processing.ProcessingIncidentEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for durable, tenant-scoped processing incidents and safe event history. */
public interface ProcessingIncidentRepository {
    void save(ProcessingIncident incident);
    Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId);
    Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);
    List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit);
    List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit);
    void saveEvent(ProcessingIncidentEvent event);
    List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit);

    static ProcessingIncidentRepository none() {
        return new ProcessingIncidentRepository() {
            @Override public void save(ProcessingIncident incident) { }
            @Override public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) { return Optional.empty(); }
            @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return Optional.empty(); }
            @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) { return List.of(); }
            @Override public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) { return List.of(); }
            @Override public void saveEvent(ProcessingIncidentEvent event) { }
            @Override public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) { return List.of(); }
        };
    }
}
