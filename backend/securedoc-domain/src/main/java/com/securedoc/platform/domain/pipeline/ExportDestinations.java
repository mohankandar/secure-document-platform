package com.securedoc.platform.domain.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0028: the local export folder has no integration connection, but it is checked and paused like any other
 * destination. Its id is derived from the tenant id (a name-based UUID), so every server agrees on it.
 */
public final class ExportDestinations {
    private static final String LOCAL_FOLDER_NAME = "lexavault:local-export-folder:";

    private ExportDestinations() { }

    public static IntegrationConnectionId localFolder(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return IntegrationConnectionId.of(UUID.nameUUIDFromBytes(
                (LOCAL_FOLDER_NAME + tenantId.value()).getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean isLocalFolder(TenantId tenantId, IntegrationConnectionId destinationId) {
        return destinationId != null && localFolder(tenantId).equals(destinationId);
    }
}
