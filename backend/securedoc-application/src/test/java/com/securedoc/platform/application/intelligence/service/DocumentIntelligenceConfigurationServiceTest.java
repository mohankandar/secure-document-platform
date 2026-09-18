package com.securedoc.platform.application.intelligence.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIntelligenceConfigurationServiceTest {

    @Test
    void updatePinsOnlyPromotedBundleAndDoesNotAdvanceLastKnownGoodAutomatically() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("profile-v2", IntelligenceBundleStatus.PROMOTED)));
        PolicyRepo policies = new PolicyRepo();
        DocumentIntelligenceConfigurationService service = service(bundles, policies);

        TenantDocumentIntelligencePolicy updated = service.update(tenant, ActorId.generate(), CorrelationId.generate(),
                DocumentIntelligenceMode.OBSERVE, "profile-v2");

        assertEquals(DocumentIntelligenceMode.OBSERVE, updated.mode());
        assertEquals("profile-v2", updated.activeBundleVersion());
        assertEquals(DocumentIntelligenceBundle.R11_BASELINE_VERSION, updated.lastKnownGoodBundleVersion());
        assertEquals(2, updated.configurationVersion());
    }


    @Test
    void candidateBundlesAreNotExposedInTenantConfigurationCatalog() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(
                bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("candidate-v2", IntelligenceBundleStatus.CANDIDATE),
                bundle("retired-v1", IntelligenceBundleStatus.RETIRED)));
        DocumentIntelligenceConfigurationService service = service(bundles, new PolicyRepo());

        var visible = service.getConfiguration(tenant).availableBundles().stream()
                .map(DocumentIntelligenceBundle::version).toList();

        assertTrue(visible.contains(DocumentIntelligenceBundle.R11_BASELINE_VERSION));
        assertTrue(visible.contains("retired-v1"));
        assertFalse(visible.contains("candidate-v2"));
    }

    @Test
    void candidateBundleCannotBeSelected() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("candidate-v2", IntelligenceBundleStatus.CANDIDATE)));
        DocumentIntelligenceConfigurationService service = service(bundles, new PolicyRepo());

        assertThrows(ValidationException.class, () -> service.update(tenant, ActorId.generate(), CorrelationId.generate(),
                DocumentIntelligenceMode.ENABLED, "candidate-v2"));
    }

    @Test
    void tenantCanMarkKnownGoodAndRollbackToIt() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("profile-v2", IntelligenceBundleStatus.PROMOTED), bundle("profile-v3", IntelligenceBundleStatus.PROMOTED)));
        PolicyRepo policies = new PolicyRepo();
        DocumentIntelligenceConfigurationService service = service(bundles, policies);

        service.update(tenant, ActorId.generate(), CorrelationId.generate(), DocumentIntelligenceMode.OBSERVE, "profile-v2");
        TenantDocumentIntelligencePolicy knownGood = service.markCurrentAsLastKnownGood(tenant, ActorId.generate(), CorrelationId.generate());
        assertEquals("profile-v2", knownGood.lastKnownGoodBundleVersion());

        service.update(tenant, ActorId.generate(), CorrelationId.generate(), DocumentIntelligenceMode.ENABLED, "profile-v3");
        TenantDocumentIntelligencePolicy rolledBack = service.rollback(tenant, ActorId.generate(), CorrelationId.generate());
        assertEquals("profile-v2", rolledBack.activeBundleVersion());
        assertEquals(DocumentIntelligenceMode.ENABLED, rolledBack.mode());
    }



    @Test
    void disablingRemainsAvailableWhenRememberedBundleHasBeenRetired() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(
                bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("profile-v1-retired", IntelligenceBundleStatus.RETIRED)));
        PolicyRepo policies = new PolicyRepo();
        policies.value = new TenantDocumentIntelligencePolicy(tenant, DocumentIntelligenceMode.ENABLED,
                "profile-v1-retired", DocumentIntelligenceBundle.R11_BASELINE_VERSION, 5, Instant.now());
        DocumentIntelligenceConfigurationService service = service(bundles, policies);

        TenantDocumentIntelligencePolicy disabled = service.update(tenant, ActorId.generate(), CorrelationId.generate(),
                DocumentIntelligenceMode.DISABLED, "profile-v1-retired");

        assertEquals(DocumentIntelligenceMode.DISABLED, disabled.mode());
        assertEquals("profile-v1-retired", disabled.activeBundleVersion());
    }

    @Test
    void retiredLastKnownGoodCannotBeNewlySelectedButRemainsRollbackEligible() {
        TenantId tenant = TenantId.generate();
        BundleRepo bundles = new BundleRepo(List.of(
                bundle(DocumentIntelligenceBundle.R11_BASELINE_VERSION, IntelligenceBundleStatus.PROMOTED),
                bundle("profile-v1-retired", IntelligenceBundleStatus.RETIRED),
                bundle("profile-v3", IntelligenceBundleStatus.PROMOTED)));
        PolicyRepo policies = new PolicyRepo();
        policies.value = new TenantDocumentIntelligencePolicy(tenant, DocumentIntelligenceMode.ENABLED,
                "profile-v3", "profile-v1-retired", 8, Instant.now());
        DocumentIntelligenceConfigurationService service = service(bundles, policies);

        assertThrows(ValidationException.class, () -> service.update(tenant, ActorId.generate(), CorrelationId.generate(),
                DocumentIntelligenceMode.ENABLED, "profile-v1-retired"));

        TenantDocumentIntelligencePolicy rolledBack = service.rollback(tenant, ActorId.generate(), CorrelationId.generate());
        assertEquals("profile-v1-retired", rolledBack.activeBundleVersion());
        assertEquals(DocumentIntelligenceMode.ENABLED, rolledBack.mode());
    }

    private DocumentIntelligenceConfigurationService service(BundleRepo bundles, PolicyRepo policies) {
        return new DocumentIntelligenceConfigurationService(bundles, policies, new AuditService(record -> { }));
    }

    private static DocumentIntelligenceBundle bundle(String version, IntelligenceBundleStatus status) {
        Instant now = Instant.now();
        return new DocumentIntelligenceBundle(version, status, "qwen3.5:4b", "r11", "r11", "r11", "none", "r11", "r11",
                "test", now, status == IntelligenceBundleStatus.PROMOTED ? now : null);
    }

    private static final class BundleRepo implements DocumentIntelligenceBundleRepository {
        private final List<DocumentIntelligenceBundle> values;
        BundleRepo(List<DocumentIntelligenceBundle> values) { this.values = values; }
        public Optional<DocumentIntelligenceBundle> findByVersion(String version) { return values.stream().filter(v -> v.version().equals(version)).findFirst(); }
        public List<DocumentIntelligenceBundle> findAll() { return values; }
    }

    private static final class PolicyRepo implements TenantDocumentIntelligencePolicyRepository {
        private TenantDocumentIntelligencePolicy value;
        public Optional<TenantDocumentIntelligencePolicy> findByTenantId(TenantId tenantId) { return Optional.ofNullable(value); }
        public void save(TenantDocumentIntelligencePolicy policy) { value = policy; }
    }
}
