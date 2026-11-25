package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.ArrayList;
import java.util.List;

/** Tests: erases as the erase handlers do, on the test's own storage and repository, and remembers what was asked. */
public final class DirectErasure implements ErasurePort {
    public final List<String> asked = new ArrayList<>();
    public RuntimeException refuse;
    private final ArtifactRepository artifacts;
    private final StoragePort storage;

    public DirectErasure(ArtifactRepository artifacts, StoragePort storage) {
        this.artifacts = artifacts;
        this.storage = storage;
    }

    @Override
    public void eraseArtifact(Artifact artifact, Reason reason) {
        asked.add(artifact.artifactType() + " " + reason.rule() + (reason.person() == null ? "" : " person"));
        if (refuse != null) throw refuse;
        if (artifact.storageReference() != null) storage.delete(artifact.storageReference());
        artifact.delete();
        artifacts.save(artifact);
    }

    @Override
    public void eraseCaseFiles(TenantId tenantId, PackageId packageId, Reason reason) {
        asked.add("CASE " + reason.rule());
        if (refuse != null) throw refuse;
    }

    @Override
    public int eraseUnusedCaseFields(int limit, Reason reason) {
        return 0;
    }
}
