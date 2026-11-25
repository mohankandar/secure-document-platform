package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.casefields.TestCaseFields;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0017 A4 (0016 answer 4): a policy save that changes nothing writes no audit record. */
class PolicySaveWithoutChangeTest {

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryPolicies policies = new InMemoryPolicies();
    private final InMemoryLifecycle lifecycle = new InMemoryLifecycle();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new TestCaseFields().settings(), policies, lifecycle, new DefaultReporting(), new AuditService(audits::add));

    @Test
    void sensitiveDataPolicySavedTwiceWithTheSameValuesIsAuditedOnce() {
        saveSensitive(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        TenantRedactionPolicy first = policies.saved;
        // Same values, types in another order, threshold with floating-point noise.
        saveSensitive(EnumSet.of(EntityType.CREDIT_SCORE, EntityType.SSN), 0.8000000000001, ReviewMode.UNCERTAIN_ONLY, true);

        assertEquals(1, count(AuditAction.SENSITIVE_DATA_POLICY_CHANGED));
        assertEquals(1, policies.saves, "nothing is saved either");
        assertEquals(first, policies.saved);
    }

    @Test
    void everySensitiveDataPolicyValueCountsAsAChange() {
        saveSensitive(EnumSet.of(EntityType.SSN), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        saveSensitive(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        saveSensitive(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.81, ReviewMode.UNCERTAIN_ONLY, true);
        saveSensitive(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.81, ReviewMode.REVIEW_ALL, true);
        saveSensitive(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.81, ReviewMode.REVIEW_ALL, false);
        assertEquals(5, count(AuditAction.SENSITIVE_DATA_POLICY_CHANGED));
    }

    @Test
    void theFirstSaveIsAlwaysRecordedEvenWithDefaultValues() {
        TenantRedactionPolicy defaults = TenantRedactionPolicy.defaults(tenant);
        saveSensitive(defaults.enabledEntityTypes(), defaults.autoRedactThreshold(), defaults.reviewMode(),
                defaults.maskSsnLastFour());
        assertEquals(1, count(AuditAction.SENSITIVE_DATA_POLICY_CHANGED), "no stored policy yet: previous=none");
    }

    @Test
    void lifecyclePolicySavedTwiceWithTheSameValuesIsAuditedOnce() {
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 72, 24, 168, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 72, 24, 168, true);
        assertEquals(1, count(AuditAction.LIFECYCLE_POLICY_CHANGED));
        assertEquals(1, lifecycle.saves);
    }

    @Test
    void everyLifecycleValueCountsAsAChange() {
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 72, 24, 168, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 48, 24, 168, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 48, 12, 168, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 48, 12, 336, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 48, 12, 336, false);
        assertEquals(5, count(AuditAction.LIFECYCLE_POLICY_CHANGED));
    }

    @Test
    void lifecycleDetailKeepsItsKeysFirstAndAddsThePreviousValues() {
        // Task 0018 A4: previous=none on the first save, previous=[…] afterwards; the four keys stay first.
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 72, 24, 168, true);
        service.updateLifecyclePolicy(tenant, ActorId.generate(), CorrelationId.generate(), 48, 24, 168, false);
        List<String> details = audits.stream().filter(r -> r.action() == AuditAction.LIFECYCLE_POLICY_CHANGED)
                .map(AuditRecord::detail).toList();
        assertEquals(List.of(
                "Lifecycle policy changed: originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=168, "
                        + "finalApprovalRequired=true, previous=none",
                "Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, "
                        + "finalApprovalRequired=false, previous=[originalRetentionHours=72, redactedCacheHours=24, "
                        + "neverExportedHoldHours=168, finalApprovalRequired=true]"), details);
        var change = com.securedoc.platform.application.compliance.PolicyHistory.parseLifecycleChange(details.get(1));
        assertEquals(72, change.previous().originalRetentionHours());
        assertEquals(48, change.now().originalRetentionHours());
    }

    private void saveSensitive(java.util.Set<EntityType> types, double threshold, ReviewMode mode, boolean mask) {
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, threshold, mode, mask);
    }

    private long count(AuditAction action) {
        return audits.stream().filter(r -> r.action() == action).count();
    }

    private static final class InMemoryPolicies implements TenantRedactionPolicyRepository {
        private TenantRedactionPolicy saved;
        private int saves;
        public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) { return Optional.ofNullable(saved); }
        public void save(TenantRedactionPolicy policy) { saved = policy; saves++; }
    }

    private static final class InMemoryLifecycle implements TenantLifecyclePolicyRepository {
        private TenantLifecyclePolicy saved;
        private int saves;
        public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) { return Optional.ofNullable(saved); }
        public void save(TenantLifecyclePolicy policy) { saved = policy; saves++; }
    }

    private static final class DefaultReporting implements ReportingConfigurationRepository {
        public ReportingConfiguration findByTenantId(TenantId tenantId) { return ReportingConfiguration.defaults(); }
        public void save(TenantId tenantId, ReportingConfiguration configuration) { }
    }
}
