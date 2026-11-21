package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;

import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Task 0028: retention per export destination, as the retention purge sees it.
 *
 * <ul>
 *   <li>The master copy (the case's redacted copies) is kept for the master destination's period from the master
 *       destination's receipt, and never deleted while the case's master export job is open.</li>
 *   <li>Each split copy is kept for its split destination's period from that destination's receipt, and never deleted
 *       while its export job is open. A split copy never received follows the master copy, as before.</li>
 *   <li>The original follows the case's own rule, but is never deleted while any export job of the case is open.</li>
 * </ul>
 */
public interface ExportCopyRetention {

    /** Hours the master copy is kept after the master destination confirmed receipt. */
    int masterCopyHours(TenantLifecyclePolicy policy);

    /** True while the case's master export job is open. */
    boolean masterExportOpen(TenantId tenantId, PackageId packageId);

    /** True while any export job of the case (master or split) is open. */
    boolean anyExportOpen(TenantId tenantId, PackageId packageId);

    /** The case's split copies that must stay now: their job is open, or their own period has not run out. */
    Set<ArtifactId> splitCopiesToKeep(TenantId tenantId, PackageId packageId, Instant now);

    /** Deletes the split copies whose own period from receipt has run out. Returns how many. */
    int purgeDueSplitCopies(Instant now, int limit, ActorId actorId, CorrelationId correlationId,
                            Consumer<Artifact> delete);

    /** No export jobs and no periods set: the master copy follows the redacted-copy retention, as before 0028. */
    static ExportCopyRetention none() {
        return new ExportCopyRetention() {
            @Override public int masterCopyHours(TenantLifecyclePolicy policy) { return policy.redactedCacheHours(); }
            @Override public boolean masterExportOpen(TenantId tenantId, PackageId packageId) { return false; }
            @Override public boolean anyExportOpen(TenantId tenantId, PackageId packageId) { return false; }
            @Override public Set<ArtifactId> splitCopiesToKeep(TenantId tenantId, PackageId packageId, Instant now) {
                return Set.of();
            }
            @Override public int purgeDueSplitCopies(Instant now, int limit, ActorId actorId,
                                                     CorrelationId correlationId, Consumer<Artifact> delete) {
                return 0;
            }
        };
    }
}
