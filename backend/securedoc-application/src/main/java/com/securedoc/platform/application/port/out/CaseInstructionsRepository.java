package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;
import java.util.UUID;

/** Task 0065: where each case's instructions are stored. The text is a file of the case, never a column. */
public interface CaseInstructionsRepository {

    /** @param fileId changes with every change of the text */
    record Stored(UUID fileId, ArtifactStorageReference reference) { }

    Optional<Stored> find(TenantId tenantId, PackageId packageId);

    void save(TenantId tenantId, PackageId packageId, Stored stored);

    void remove(TenantId tenantId, PackageId packageId);
}
