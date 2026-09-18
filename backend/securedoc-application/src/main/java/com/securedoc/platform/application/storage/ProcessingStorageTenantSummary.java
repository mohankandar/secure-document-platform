package com.securedoc.platform.application.storage;

import java.util.UUID;

/** Safe tenant identity exposed to Platform Operations for processing-storage assignment. */
public record ProcessingStorageTenantSummary(
        UUID tenantId,
        String name,
        String slug,
        String status) {
}
