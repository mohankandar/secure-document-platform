package com.securedoc.platform.domain.lifecycle;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0104, step 3: the export-cycle limit counts per batch. A new batch is never refused for the cycles of the batches
 * before it; re-exports of one batch stay limited to {@link TenantLifecyclePolicy#MAX_SUCCESSFUL_EXPORT_CYCLES}.
 */
class BatchExportCycleLimitTest {

    private final TenantId tenant = TenantId.of(UUID.randomUUID());
    private final TenantLifecyclePolicy policy = TenantLifecyclePolicy.defaults(tenant);
    private final int max = TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES;

    @Test
    void oneBatchKeepsTheLimitAsBefore() {
        PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
        for (int cycle = 1; cycle <= max; cycle++) {
            assertEquals(cycle, life.nextExportCycle());
            life.recordSuccessfulExport(cycle, Instant.now(), policy);
        }
        assertThrows(IllegalStateException.class, life::nextExportCycle);
        assertThrows(IllegalStateException.class, () -> life.recordSuccessfulExport(max + 1, Instant.now(), policy));
    }

    @Test
    void aNewBatchAfterTheLimitIsDeliveredAndItsOwnReExportsAreLimited() {
        PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
        for (int cycle = 1; cycle <= max; cycle++) life.recordSuccessfulExport(cycle, Instant.now(), policy);

        // batch 2 arrives: the cycles of batch 1 do not count against it
        int before = max;
        for (int cycle = max + 1; cycle <= 2 * max; cycle++) {
            assertEquals(cycle, life.nextExportCycle(before));
            life.recordSuccessfulExport(cycle, Instant.now(), policy, before);
        }
        assertThrows(IllegalStateException.class, () -> life.nextExportCycle(before));
        assertThrows(IllegalStateException.class,
                () -> life.recordSuccessfulExport(2 * max + 1, Instant.now(), policy, before));

        // batch 3: again its own limit
        assertEquals(2 * max + 1, life.nextExportCycle(2 * max));
    }

    @Test
    void aNextBatchStopsTheLastDeliverysKeepPeriodUntilItIsDeliveredItself() {
        PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
        Instant delivered = Instant.parse("2026-11-01T10:00:00Z");
        life.recordSuccessfulExport(1, delivered, policy);
        assertTrue(life.originalDeleteAt().isPresent());

        life.startNextBatch(delivered.plusSeconds(3600));
        Instant longAfter = delivered.plus(java.time.Duration.ofDays(400));
        assertFalse(life.isFinalPurgeDue(longAfter), "batch 2 is never purged while it is processing or in Review");
        assertFalse(life.isRedactedPurgeDue(longAfter));

        Instant secondDelivery = delivered.plus(java.time.Duration.ofDays(45));
        life.recordSuccessfulExport(2, secondDelivery, policy, 1);
        assertEquals(secondDelivery.plus(java.time.Duration.ofHours(policy.originalRetentionHours())),
                life.originalDeleteAt().orElseThrow(), "the keep period runs from the last delivery");
    }

    @Test
    void aPurgedCaseTakesNoNextBatch() {
        PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
        life.recordSuccessfulExport(1, Instant.now(), policy);
        life.markFinalPurged(Instant.now());
        assertThrows(IllegalStateException.class, () -> life.startNextBatch(Instant.now()));
    }

    @Test
    void cyclesBeforeTheBatchOutsideTheCasesCyclesAreRefused() {
        PackageLifecycle life = PackageLifecycle.create(PackageId.generate(), tenant);
        life.recordSuccessfulExport(1, Instant.now(), policy);
        assertThrows(IllegalStateException.class, () -> life.nextExportCycle(2));
        assertThrows(IllegalStateException.class, () -> life.nextExportCycle(-1));
    }
}
