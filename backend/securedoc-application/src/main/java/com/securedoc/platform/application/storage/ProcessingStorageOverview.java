package com.securedoc.platform.application.storage;

import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageNodeValidation;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;

import java.util.List;

public record ProcessingStorageOverview(
        ProcessingStorageAssignment deploymentDefault,
        List<ProcessingStorageAssignment> tenantOverrides,
        List<ProcessingStorageProvider> providers,
        List<ProcessingStorageNodeValidation> nodeValidations,
        List<ProcessingStorageTenantSummary> tenants,
        long activeNodeCount,
        boolean ready) {
}
