package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.FinishedJobCleanupPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0034: the clean-up run (batches, the audit record, the cut-off). The SQL rule is in the PostgreSQL test. */
class FinishedJobCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final ActorId SYSTEM = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    /** Removable rows per tenant; each delete takes at most the limit. */
    static final class FakeStore implements FinishedJobCleanupPort {
        final Map<TenantId, Integer> jobs = new HashMap<>();
        final Map<TenantId, Integer> alerts = new HashMap<>();
        final List<Integer> batches = new ArrayList<>();
        final List<Instant> cutoffs = new ArrayList<>();
        TenantId failing;
        final List<TenantId> tenants = new ArrayList<>();

        @Override public List<TenantId> tenantIds() { return tenants; }

        @Override public int deleteFinishedJobs(TenantId tenantId, Instant cutoff, int limit) {
            if (tenantId.equals(failing)) throw new IllegalStateException("database gone");
            cutoffs.add(cutoff);
            int left = jobs.getOrDefault(tenantId, 0);
            int removed = Math.min(left, limit);
            jobs.put(tenantId, left - removed);
            batches.add(removed);
            return removed;
        }

        @Override public int deleteClearedAlerts(TenantId tenantId, Instant cutoff) {
            return alerts.remove(tenantId) == null ? 0 : 1;
        }
    }

    private final AuditService audit = mock(AuditService.class);

    private FinishedJobCleanupService service(FakeStore store, int days, int batch) {
        return new FinishedJobCleanupService(store, audit, new FinishedJobCleanupSettings(days, batch));
    }

    @Test
    void removesInBatchesUntilABatchComesBackShortAndAuditsCountsAndTheCutOffOnly() {
        FakeStore store = new FakeStore();
        TenantId tenant = TenantId.generate();
        store.tenants.add(tenant);
        store.jobs.put(tenant, 2_500);
        store.alerts.put(tenant, 1);

        var result = service(store, 90, 1000).run(NOW, SYSTEM, CorrelationId.generate());

        assertEquals(2_500, result.jobsRemoved());
        assertEquals(1, result.alertsRemoved());
        assertEquals(List.of(1000, 1000, 500), store.batches, "1,000 rows per batch, then the rest");
        assertEquals(NOW.minus(Duration.ofDays(90)), store.cutoffs.get(0));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, times(1)).recordSuccess(eq(tenant), eq(SYSTEM), eq(AuditAction.PIPELINE_JOBS_CLEANED),
                eq("Tenant"), eq(tenant.toString()), any(CorrelationId.class), detail.capture());
        assertEquals("Finished jobs cleaned: jobs=2500, alerts=1, cutoff=2026-08-24", detail.getValue());
    }

    @Test
    void aRunThatRemovesNothingWritesNoAuditRecord() {
        FakeStore store = new FakeStore();
        store.tenants.add(TenantId.generate());

        var result = service(store, 90, 1000).run(NOW, SYSTEM, CorrelationId.generate());

        assertEquals(0, result.jobsRemoved());
        assertEquals(List.of(0), store.batches, "one empty batch, then stop");
        verifyNoInteractions(audit);
    }

    @Test
    void inlineModeHasNoJobsAndTheRunDoesNothing() {
        var result = new FinishedJobCleanupService(FinishedJobCleanupPort.none(), audit,
                FinishedJobCleanupSettings.defaults()).run(NOW, SYSTEM, CorrelationId.generate());
        assertEquals(new FinishedJobCleanupService.Result(0, 0), result);
        verifyNoInteractions(audit);
    }

    @Test
    void oneRunTakesAtMostItsBatchLimitPerTenantAndTheRestWaitsForTheNextRun() {
        FakeStore store = new FakeStore();
        TenantId tenant = TenantId.generate();
        store.tenants.add(tenant);
        store.jobs.put(tenant, FinishedJobCleanupService.MAX_BATCHES_PER_TENANT * 10 + 7);

        var first = service(store, 7, 10).run(NOW, SYSTEM, CorrelationId.generate());
        assertEquals(FinishedJobCleanupService.MAX_BATCHES_PER_TENANT * 10, first.jobsRemoved());
        var second = service(store, 7, 10).run(NOW, SYSTEM, CorrelationId.generate());
        assertEquals(7, second.jobsRemoved());
    }

    @Test
    void eachTenantGetsItsOwnRecordAndAFailingTenantDoesNotStopTheOthers() {
        FakeStore store = new FakeStore();
        TenantId broken = TenantId.generate();
        TenantId a = TenantId.generate();
        TenantId b = TenantId.generate();
        store.tenants.addAll(List.of(broken, a, b));
        store.failing = broken;
        store.jobs.put(a, 3);
        store.jobs.put(b, 0);
        store.alerts.put(b, 1);

        var result = service(store, 90, 1000).run(NOW, SYSTEM, CorrelationId.generate());

        assertEquals(3, result.jobsRemoved());
        assertEquals(1, result.alertsRemoved());
        verify(audit).recordSuccess(eq(a), any(), eq(AuditAction.PIPELINE_JOBS_CLEANED), anyString(), anyString(),
                any(), eq("Finished jobs cleaned: jobs=3, alerts=0, cutoff=2026-08-24"));
        verify(audit).recordSuccess(eq(b), any(), eq(AuditAction.PIPELINE_JOBS_CLEANED), anyString(), anyString(),
                any(), eq("Finished jobs cleaned: jobs=0, alerts=1, cutoff=2026-08-24"));
        verify(audit, never()).recordSuccess(eq(broken), any(), any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void theKeepTimeAndBatchSizeHaveTheirDefaultsAndBounds() {
        FinishedJobCleanupSettings d = FinishedJobCleanupSettings.defaults();
        assertEquals(90, d.keepFinishedDays());
        assertEquals(1000, d.batchSize());
        assertDoesNotThrow(() -> new FinishedJobCleanupSettings(7, 1));
        assertDoesNotThrow(() -> new FinishedJobCleanupSettings(3650, 10_000));
        for (int days : new int[]{-1, 0, 6, 3651, Integer.MAX_VALUE}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new FinishedJobCleanupSettings(days, 1000), String.valueOf(days));
            assertEquals("securedoc.processing.export-jobs.keep-finished-days must be between 7 and 3650 days",
                    e.getMessage(), "names the setting and its range, never the value");
        }
        for (int batch : new int[]{0, -5, 10_001}) {
            assertThrows(IllegalArgumentException.class, () -> new FinishedJobCleanupSettings(90, batch));
        }
    }
}
