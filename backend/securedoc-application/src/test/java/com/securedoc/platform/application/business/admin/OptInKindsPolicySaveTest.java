package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.exception.ValidationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0043: a Business Admin can switch the opt-in pattern kinds on and off; the four V1 kinds are unchanged. */
class OptInKindsPolicySaveTest {

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryPolicies policies = new InMemoryPolicies();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new NoDefinitions(), policies, new NoLifecycle(), new DefaultReporting(), new AuditService(audits::add));

    @Test
    void theOptInKindsAreOptInAndSeparateFromTheV1Four() {
        assertEquals(EnumSet.of(EntityType.BANK_ROUTING_NUMBER, EntityType.BANK_ACCOUNT_NUMBER, EntityType.ITIN,
                EntityType.EIN, EntityType.DRIVERS_LICENSE_NUMBER, EntityType.PASSPORT_NUMBER,
                EntityType.SIGNATURE, EntityType.FACE_PHOTO), // task 0064
                EnumSet.copyOf(TenantRedactionPolicy.optInTypes()));
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE), EnumSet.copyOf(TenantRedactionPolicy.v1ConfigurableTypes()));
        assertEquals(12, TenantRedactionPolicy.configurableTypes().size());
        assertFalse(TenantRedactionPolicy.configurableTypes().contains(EntityType.FINANCIAL_ACCOUNT), "stays unused");
        assertFalse(TenantRedactionPolicy.configurableTypes().contains(EntityType.GOVERNMENT_ID), "stays unused");
    }

    @Test
    void anAdminSwitchesANewKindOnAndOffAgain() {
        Set<EntityType> on = EnumSet.of(EntityType.SSN, EntityType.BANK_ROUTING_NUMBER, EntityType.ITIN);
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), on, 0.80,
                ReviewMode.UNCERTAIN_ONLY, true);
        assertEquals(on, policies.saved.enabledEntityTypes());
        assertTrue(lastDetail().contains("enabledTypes=BANK_ROUTING_NUMBER,ITIN,SSN"), lastDetail());

        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(),
                EnumSet.of(EntityType.SSN), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        assertEquals(Set.of(EntityType.SSN), policies.saved.enabledEntityTypes());
        assertTrue(lastDetail().contains("previous=[enabledCount=3"), lastDetail());
    }

    @Test
    void onlyNewKindsIsAValidPolicy() {
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(),
                EnumSet.of(EntityType.EIN), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        assertEquals(Set.of(EntityType.EIN), policies.saved.enabledEntityTypes());
    }

    @Test
    void theUnusedGenericKindsAreStillRefused() {
        for (EntityType unused : List.of(EntityType.FINANCIAL_ACCOUNT, EntityType.GOVERNMENT_ID, EntityType.CUSTOM)) {
            assertThrows(ValidationException.class, () -> service.updateSensitiveDataPolicy(tenant, ActorId.generate(),
                    CorrelationId.generate(), EnumSet.of(EntityType.SSN, unused), 0.80, ReviewMode.UNCERTAIN_ONLY, true));
        }
    }

    @Test
    void everyKindOnStillFitsTheAuditDetail() {
        Set<EntityType> all = TenantRedactionPolicy.configurableTypes();
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), all, 0.99,
                ReviewMode.UNCERTAIN_ONLY, false);
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), all, 0.50,
                ReviewMode.UNCERTAIN_ONLY, false, false, 100, 10, false, 0.70);
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), all, 0.50,
                ReviewMode.UNCERTAIN_ONLY, false, false, 101, 11, false, 0.71);
        assertTrue(lastDetail().contains("visionEnabled=false, visionOcrThreshold=0.71"), lastDetail());
        assertFalse(lastDetail().contains("[truncated]"));
        assertTrue(lastDetail().startsWith("Sensitive-data policy changed: enabledCount=12, threshold=0.50"));
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
