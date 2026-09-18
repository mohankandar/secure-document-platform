package com.securedoc.platform.application.intelligence.service;

import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.application.port.out.PackageProcessingIntelligenceSnapshotRepository;
import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIntelligenceExecutionServiceTest {

    @Test
    void capturesOnlyOncePerPackageCycleAndObserveCannotInfluenceDecisions() {
        TenantId tenant = TenantId.generate();
        PackageId pkg = PackageId.generate();
        PolicyRepo policies = new PolicyRepo(new TenantDocumentIntelligencePolicy(tenant, DocumentIntelligenceMode.OBSERVE,
                "profile-v2", DocumentIntelligenceBundle.R11_BASELINE_VERSION, 4, Instant.now()));
        SnapshotRepo snapshots = new SnapshotRepo();
        DocumentIntelligenceExecutionService service = new DocumentIntelligenceExecutionService(
                policies, new BundleRepo(List.of(bundle("profile-v2", IntelligenceBundleStatus.PROMOTED))), snapshots);

        PackageProcessingIntelligenceSnapshot first = service.ensureSnapshot(tenant, pkg, 1, CorrelationId.generate());
        PackageProcessingIntelligenceSnapshot second = service.ensureSnapshot(tenant, pkg, 1, CorrelationId.generate());

        assertEquals(first.id(), second.id());
        assertEquals(1, snapshots.values.size());
        assertEquals(DocumentIntelligenceMode.OBSERVE, first.effectiveMode());
        assertTrue(first.intelligenceExecutionAllowed());
        assertFalse(first.decisionInfluenceAllowed());
        assertTrue(first.baselineDiscoveryRequired());
        assertEquals("profile-v2", first.bundleVersion());
    }

    @Test
    void missingEnabledBundleDegradesToDisabledBaselineInsteadOfFailingProcessingPolicy() {
        TenantId tenant = TenantId.generate();
        PackageId pkg = PackageId.generate();
        PolicyRepo policies = new PolicyRepo(new TenantDocumentIntelligencePolicy(tenant, DocumentIntelligenceMode.ENABLED,
                "missing-v9", DocumentIntelligenceBundle.R11_BASELINE_VERSION, 7, Instant.now()));
        SnapshotRepo snapshots = new SnapshotRepo();
        DocumentIntelligenceExecutionService service = new DocumentIntelligenceExecutionService(
                policies, new BundleRepo(List.of()), snapshots);

        PackageProcessingIntelligenceSnapshot snapshot = service.ensureSnapshot(tenant, pkg, 1, CorrelationId.generate());

        assertEquals(DocumentIntelligenceMode.ENABLED, snapshot.requestedMode());
        assertEquals(DocumentIntelligenceMode.DISABLED, snapshot.effectiveMode());
        assertTrue(snapshot.fallbackToBaseline());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, snapshot.bundleVersion());
        assertFalse(snapshot.decisionInfluenceAllowed());
    }


    @Test
    void retiredBundleRemainsExecutableForExistingTenantPinAndRollbackTarget() {
        TenantId tenant = TenantId.generate();
        PackageId pkg = PackageId.generate();
        PolicyRepo policies = new PolicyRepo(new TenantDocumentIntelligencePolicy(tenant, DocumentIntelligenceMode.ENABLED,
                "profile-v1-retired", "profile-v1-retired", 9, Instant.now()));
        SnapshotRepo snapshots = new SnapshotRepo();
        DocumentIntelligenceExecutionService service = new DocumentIntelligenceExecutionService(
                policies, new BundleRepo(List.of(bundle("profile-v1-retired", IntelligenceBundleStatus.RETIRED))), snapshots);

        PackageProcessingIntelligenceSnapshot snapshot = service.ensureSnapshot(tenant, pkg, 1, CorrelationId.generate());

        assertEquals(DocumentIntelligenceMode.ENABLED, snapshot.effectiveMode());
        assertEquals("profile-v1-retired", snapshot.bundleVersion());
        assertFalse(snapshot.fallbackToBaseline());
        assertTrue(snapshot.decisionInfluenceAllowed());
        assertTrue(snapshot.baselineDiscoveryRequired());
    }

    private static DocumentIntelligenceBundle bundle(String version, IntelligenceBundleStatus status) {
        Instant now = Instant.now();
        return new DocumentIntelligenceBundle(version, status, "qwen3.5:4b", "r11", "r11", "r11", "profile-v2", "r11", "r11",
                "test", now, status == IntelligenceBundleStatus.PROMOTED ? now : null);
    }

    private static final class BundleRepo implements DocumentIntelligenceBundleRepository {
        private final List<DocumentIntelligenceBundle> values;
        BundleRepo(List<DocumentIntelligenceBundle> values) { this.values = values; }
        public Optional<DocumentIntelligenceBundle> findByVersion(String version) { return values.stream().filter(v -> v.version().equals(version)).findFirst(); }
        public List<DocumentIntelligenceBundle> findAll() { return values; }
    }

    private static final class PolicyRepo implements TenantDocumentIntelligencePolicyRepository {
        private final TenantDocumentIntelligencePolicy policy;
        PolicyRepo(TenantDocumentIntelligencePolicy policy) { this.policy = policy; }
        public Optional<TenantDocumentIntelligencePolicy> findByTenantId(TenantId tenantId) { return Optional.of(policy); }
        public void save(TenantDocumentIntelligencePolicy policy) { throw new UnsupportedOperationException(); }
    }

    private static final class SnapshotRepo implements PackageProcessingIntelligenceSnapshotRepository {
        private final List<PackageProcessingIntelligenceSnapshot> values = new ArrayList<>();
        public Optional<PackageProcessingIntelligenceSnapshot> findByTenantIdAndPackageIdAndProcessingCycle(TenantId tenantId, PackageId packageId, int processingCycle) {
            return values.stream().filter(v -> v.tenantId().equals(tenantId) && v.packageId().equals(packageId) && v.processingCycle() == processingCycle).findFirst();
        }
        public Optional<PackageProcessingIntelligenceSnapshot> findLatestByTenantIdAndPackageId(TenantId tenantId, PackageId packageId) {
            return values.stream().filter(v -> v.tenantId().equals(tenantId) && v.packageId().equals(packageId))
                    .max(java.util.Comparator.comparingInt(PackageProcessingIntelligenceSnapshot::processingCycle));
        }
        public PackageProcessingIntelligenceSnapshot saveIfAbsent(PackageProcessingIntelligenceSnapshot snapshot) {
            return findByTenantIdAndPackageIdAndProcessingCycle(snapshot.tenantId(), snapshot.packageId(), snapshot.processingCycle())
                    .orElseGet(() -> { values.add(snapshot); return snapshot; });
        }
    }
}
