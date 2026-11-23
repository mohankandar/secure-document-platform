package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0044: the entity's "searchable redacted pages" switch, on by default. */
class SearchableRedactedPagesSettingTest {

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryPolicies policies = new InMemoryPolicies();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new NoDefinitions(), policies, new NoLifecycle(), new DefaultReporting(), new AuditService(audits::add));
    private final Set<EntityType> types = EnumSet.of(EntityType.SSN);

    @Test
    void onByDefault() {
        assertTrue(TenantRedactionPolicy.defaults(tenant).searchableRedactedPages());
        assertTrue(new TenantRedactionPolicy(tenant, types, 0.8, ReviewMode.UNCERTAIN_ONLY, true,
                java.time.Instant.now()).searchableRedactedPages(), "a policy built the pre-0044 way is on");
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, 0.8,
                ReviewMode.UNCERTAIN_ONLY, true);
        assertTrue(policies.saved.searchableRedactedPages());
        assertFalse(lastDetail().contains("searchableRedactedPages"), "on is not written: details stay as before");
    }

    @Test
    void turningItOffIsSavedAndAudited_andTheOldCallKeepsIt() {
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, false);
        assertFalse(policies.saved.searchableRedactedPages());
        assertTrue(lastDetail().contains("preset=BALANCED, searchableRedactedPages=false, previous=none"), lastDetail());

        // A save that does not send the switch (the old call) keeps it off.
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, 0.7,
                ReviewMode.UNCERTAIN_ONLY, true);
        assertFalse(policies.saved.searchableRedactedPages());

        int before = audits.size();
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, 0.7,
                ReviewMode.UNCERTAIN_ONLY, true, false);
        assertEquals(before, audits.size(), "nothing changed: no audit record");

        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, 0.7,
                ReviewMode.UNCERTAIN_ONLY, true, true);
        assertTrue(policies.saved.searchableRedactedPages());
        assertTrue(lastDetail().endsWith("searchableRedactedPages=false]"), "the previous policy shows it was off");
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

    private static final class NoDefinitions implements BusinessIdentifierDefinitionRepository {
        public List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId) { return List.of(); }
        public List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId) { return List.of(); }
        public void save(BusinessIdentifierDefinition definition) { }
        public void saveAll(List<BusinessIdentifierDefinition> definitions) { }
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
