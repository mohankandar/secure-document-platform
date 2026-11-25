package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.casefields.TestCaseFields;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.application.compliance.PolicyHistory;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.RegulationPack;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0062: packs, styles and locked items through the Business Admin save; audited; a save of an old client keeps them. */
class RedactionCatalogSaveTest {

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryPolicies policies = new InMemoryPolicies();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new TestCaseFields().settings(), policies, new NoLifecycle(), new DefaultReporting(), new AuditService(audits::add));

    @Test
    void packsAndStylesAreSavedAndAuditedAndKeptByAClientThatDoesNotSendThem() {
        save(EnumSet.of(EntityType.SSN, EntityType.ITIN), Set.of(RegulationPack.FRCP_5_2), Set.of(EntityType.SSN));
        assertEquals(Set.of(RegulationPack.FRCP_5_2), policies.saved.packs());
        assertEquals(Set.of(EntityType.SSN), policies.saved.keepLastFour());
        assertTrue(lastDetail().contains(", packs=FRCP_5_2, keepLastFour=SSN, previous=none"), lastDetail());

        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(),
                EnumSet.of(EntityType.SSN), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        assertEquals(Set.of(RegulationPack.FRCP_5_2), policies.saved.packs(), "kept; ITIN is fine-tuned off");
        assertEquals(Set.of(EntityType.SSN), policies.saved.keepLastFour());
        int before = audits.size();
        save(EnumSet.of(EntityType.SSN), Set.of(RegulationPack.FRCP_5_2), Set.of(EntityType.SSN));
        assertEquals(before, audits.size(), "nothing changed: no audit record");
        save(EnumSet.of(EntityType.SSN), Set.of(), Set.of());
        assertTrue(lastDetail().contains(", packs=FRCP_5_2, keepLastFour=SSN]"), lastDetail());
        assertTrue(policies.saved.packs().isEmpty() && policies.saved.keepLastFour().isEmpty());
    }

    @Test
    void aStyleForALockedItemIsRefused() {
        for (EntityType kind : List.of(EntityType.CREDIT_CARD_NUMBER, EntityType.CVV, EntityType.PIN,
                EntityType.DATE_OF_BIRTH, EntityType.CREDIT_SCORE)) {
            assertThrows(ValidationException.class, () -> save(EnumSet.of(EntityType.SSN), Set.of(), Set.of(kind)));
        }
        assertTrue(audits.isEmpty());
    }

    @Test
    void theFirstSaveOfAnEntityFromBeforeTheCatalogTurnsTheLockedItemsOnAndSaysSo() {
        policies.saved = TenantRedactionPolicy.defaultsBeforeCatalog(tenant);
        save(policies.saved.enabledEntityTypes(), Set.of(), Set.of());
        assertTrue(policies.saved.lockedItemsOn());
        assertTrue(lastDetail().startsWith("Sensitive-data policy changed: enabledCount=6"), lastDetail());
        assertTrue(lastDetail().contains("previous=[enabledCount=4"), lastDetail());
    }

    @Test
    void theLongestDetailIsNotTruncatedAndIsReadBack() {
        Set<RegulationPack> packs = EnumSet.allOf(RegulationPack.class);
        for (double threshold : List.of(0.50, 0.51)) {
            service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(),
                    RedactionCatalog.kinds(), threshold, ReviewMode.UNCERTAIN_ONLY, false, false, 100, 10, packs,
                    RedactionCatalog.lastFourAllowed());
        }
        assertFalse(lastDetail().contains("[truncated]"), lastDetail().length() + " characters");
        var change = PolicyHistory.parseSensitive(lastDetail());
        assertTrue(change.previousRecorded());
        assertEquals(4, change.now().packs().size());
        assertEquals(7, change.previous().keepLastFour().size());
        assertEquals(List.of("Redacted without review at: 50% → 51%"), PolicyHistory.changeLines(change));
        assertEquals("FRCP 5.2 (court filings)", PolicyHistory.packName("FRCP_5_2"));
    }

    private void save(Set<EntityType> kinds, Set<RegulationPack> packs, Set<EntityType> lastFour) {
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), kinds, 0.80,
                ReviewMode.UNCERTAIN_ONLY, true, true, null, null, packs, lastFour);
    }

    private String lastDetail() {
        return audits.stream().filter(r -> r.action() == AuditAction.SENSITIVE_DATA_POLICY_CHANGED)
                .reduce((a, b) -> b).orElseThrow().detail();
    }

    private static final class InMemoryPolicies implements TenantRedactionPolicyRepository {
        private TenantRedactionPolicy saved;
        public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) { return Optional.ofNullable(saved); }
        public void save(TenantRedactionPolicy policy) { saved = policy; }
    }

    private static final class NoLifecycle implements TenantLifecyclePolicyRepository {
        public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) { return Optional.empty(); }
        public void save(TenantLifecyclePolicy policy) { }
    }

    private static final class DefaultReporting implements ReportingConfigurationRepository {
        public ReportingConfiguration findByTenantId(TenantId tenantId) { return ReportingConfiguration.defaults(); }
        public void save(TenantId tenantId, ReportingConfiguration configuration) { }
    }
}
