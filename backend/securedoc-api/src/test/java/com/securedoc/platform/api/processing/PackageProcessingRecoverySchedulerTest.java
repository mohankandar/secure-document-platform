package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageProcessingLeasePort;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PackageProcessingRecoverySchedulerTest {

    @Test
    void legacyProcessingPackageWithoutLeaseIsRedispatchedAsRecoveryAfterGrace() {
        TenantId tenantId = TenantId.generate();
        DocumentPackage pkg = processingPackage(tenantId);
        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        FakeLeasePort leases = new FakeLeasePort();
        leases.orphans = List.of(new PackageProcessingLeasePort.OrphanedProcessingSnapshot(
                pkg.id(), tenantId, Instant.now().minus(Duration.ofDays(2))));
        CapturingDispatcher dispatcher = new CapturingDispatcher();

        PackageProcessingRecoveryScheduler scheduler = new PackageProcessingRecoveryScheduler(
                packages, leases, dispatcher, null, true, 100, 3, 60_000, 30_000, "APP-01");

        scheduler.recoverOrphanedWork();

        assertEquals(1, dispatcher.commands.size());
        assertTrue(dispatcher.commands.get(0).recovery(), "legacy PROCESSING work must use the recovery path");
        assertEquals(pkg.id(), dispatcher.commands.get(0).packageId());
    }

    @Test
    void expiredAttemptBelowBudgetIsRedispatchedButExhaustedAttemptIsNot() {
        TenantId tenantId = TenantId.generate();
        DocumentPackage pkg = processingPackage(tenantId);
        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        FakeLeasePort leases = new FakeLeasePort();
        Instant past = Instant.now().minusSeconds(60);
        leases.expired = List.of(new PackageProcessingLeasePort.LeaseSnapshot(
                pkg.id(), tenantId, "dead-owner", 2, 2, past.minusSeconds(30), past.minusSeconds(20), past));
        CapturingDispatcher dispatcher = new CapturingDispatcher();

        PackageProcessingRecoveryScheduler scheduler = new PackageProcessingRecoveryScheduler(
                packages, leases, dispatcher, null, true, 100, 3, 60_000, 30_000, "APP-01");
        scheduler.recoverOrphanedWork();

        assertEquals(1, dispatcher.commands.size());
        assertTrue(dispatcher.commands.get(0).recovery());

        dispatcher.commands.clear();
        leases.expired = List.of(new PackageProcessingLeasePort.LeaseSnapshot(
                pkg.id(), tenantId, "dead-owner", 3, 3, past.minusSeconds(30), past.minusSeconds(20), past));
        leases.exhaustionClaims = 0;
        scheduler.recoverOrphanedWork();

        assertTrue(dispatcher.commands.isEmpty(), "attempt 3 must not create an automatic attempt 4");
        assertEquals(1, leases.exhaustionClaims, "scheduler should attempt the atomic terminal fencing claim");
    }

    @Test
    void failedOrphanDispatchDoesNotBlockNextPackage() {
        TenantId tenant = TenantId.generate();
        DocumentPackage first = processingPackage(tenant);
        DocumentPackage second = processingPackage(tenant);
        PackageRepository packages = mock(PackageRepository.class);
        PackageProcessingLeasePort leases = mock(PackageProcessingLeasePort.class);
        PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);

        when(leases.currentTime()).thenReturn(Instant.now());
        when(packages.findByStatus(eq(PackageStatus.SUBMITTED), anyInt())).thenReturn(List.of());
        when(leases.findProcessingWithoutLease(any(Duration.class), anyInt())).thenReturn(List.of(
                new PackageProcessingLeasePort.OrphanedProcessingSnapshot(first.id(), tenant, Instant.now().minusSeconds(3600)),
                new PackageProcessingLeasePort.OrphanedProcessingSnapshot(second.id(), tenant, Instant.now().minusSeconds(3600))));
        when(packages.findByIdAndTenantId(first.id(), tenant)).thenReturn(Optional.of(first));
        when(packages.findByIdAndTenantId(second.id(), tenant)).thenReturn(Optional.of(second));
        when(leases.findExpired(anyInt())).thenReturn(List.of());
        doAnswer(invocation -> {
            TriggerPackageProcessingCommand command = invocation.getArgument(0);
            if (command.packageId().equals(first.id())) throw new IllegalStateException("temporary dispatch outage");
            return null;
        }).when(dispatcher).dispatch(any(TriggerPackageProcessingCommand.class));

        new PackageProcessingRecoveryScheduler(packages, leases, dispatcher, null,
                true, 100, 3, 60_000, 30_000, "APP-01").recoverOrphanedWork();

        verify(dispatcher, times(2)).dispatch(any(TriggerPackageProcessingCommand.class));
        verify(dispatcher).dispatch(argThat(command -> command.packageId().equals(second.id()) && command.recovery()));
    }

    @Test
    void failedExpiredLeaseDispatchDoesNotBlockNextExpiredLease() {
        TenantId tenant = TenantId.generate();
        DocumentPackage first = processingPackage(tenant);
        DocumentPackage second = processingPackage(tenant);
        PackageRepository packages = mock(PackageRepository.class);
        PackageProcessingLeasePort leases = mock(PackageProcessingLeasePort.class);
        PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
        Instant past = Instant.now().minusSeconds(60);

        when(leases.currentTime()).thenReturn(Instant.now());
        when(packages.findByStatus(eq(PackageStatus.SUBMITTED), anyInt())).thenReturn(List.of());
        when(leases.findProcessingWithoutLease(any(Duration.class), anyInt())).thenReturn(List.of());
        when(leases.findExpired(anyInt())).thenReturn(List.of(
                new PackageProcessingLeasePort.LeaseSnapshot(first.id(), tenant, "failed-node", 1, 1,
                        past.minusSeconds(30), past.minusSeconds(20), past),
                new PackageProcessingLeasePort.LeaseSnapshot(second.id(), tenant, "failed-node", 2, 1,
                        past.minusSeconds(30), past.minusSeconds(20), past)));
        when(packages.findByIdAndTenantId(first.id(), tenant)).thenReturn(Optional.of(first));
        when(packages.findByIdAndTenantId(second.id(), tenant)).thenReturn(Optional.of(second));
        doAnswer(invocation -> {
            TriggerPackageProcessingCommand command = invocation.getArgument(0);
            if (command.packageId().equals(first.id())) throw new IllegalStateException("temporary dispatch outage");
            return null;
        }).when(dispatcher).dispatch(any(TriggerPackageProcessingCommand.class));

        new PackageProcessingRecoveryScheduler(packages, leases, dispatcher, null,
                true, 100, 3, 60_000, 30_000, "APP-01").recoverOrphanedWork();

        verify(dispatcher, times(2)).dispatch(any(TriggerPackageProcessingCommand.class));
        verify(dispatcher).dispatch(argThat(command -> command.packageId().equals(second.id()) && command.recovery()));
    }

    private static DocumentPackage processingPackage(TenantId tenantId) {
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(tenantId, pkg.id(), FileName.of("legacy.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("legacy"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        return pkg;
    }

    private static final class MutablePackageRepository implements PackageRepository {
        private final DocumentPackage pkg;
        private MutablePackageRepository(DocumentPackage pkg) { this.pkg = pkg; }
        @Override public void save(DocumentPackage documentPackage) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return pkg.id().equals(id) && pkg.tenantId().equals(tenantId) ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) {
            return pkg.status() == status ? List.of(pkg) : List.of();
        }
    }

    private static final class CapturingDispatcher implements PackageProcessingDispatcher {
        private final List<TriggerPackageProcessingCommand> commands = new ArrayList<>();
        @Override public void dispatch(TriggerPackageProcessingCommand command) { commands.add(command); }
    }

    private static final class FakeLeasePort implements PackageProcessingLeasePort {
        private List<OrphanedProcessingSnapshot> orphans = List.of();
        private List<LeaseSnapshot> expired = List.of();
        private int exhaustionClaims;

        @Override public Optional<LeaseSnapshot> acquireOrRenew(PackageId packageId, TenantId tenantId, String ownerId, Duration leaseDuration) { return Optional.empty(); }
        @Override public Optional<LeaseSnapshot> current(PackageId packageId) { return Optional.empty(); }
        @Override public List<LeaseSnapshot> findExpired(int limit) { return expired; }
        @Override public List<OrphanedProcessingSnapshot> findProcessingWithoutLease(Duration staleAge, int limit) { return orphans; }
        @Override public Instant currentTime() { return Instant.now(); }
        @Override public boolean isCurrent(PackageId packageId, String ownerId, long fencingToken) { return false; }
        @Override public boolean release(PackageId packageId, String ownerId, long fencingToken) { return true; }
        @Override public Optional<LeaseSnapshot> claimExpiredForExhaustion(PackageId packageId, TenantId tenantId,
                                                                          long expectedFencingToken, int expectedAttemptCount,
                                                                          String ownerId, Duration leaseDuration) {
            exhaustionClaims++;
            return Optional.empty();
        }
    }
}
