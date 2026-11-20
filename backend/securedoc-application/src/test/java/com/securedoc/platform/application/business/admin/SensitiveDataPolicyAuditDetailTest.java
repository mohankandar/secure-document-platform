package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0016 A3: the policy-change audit detail keeps its old keys and appends type names, preset and previous. */
class SensitiveDataPolicyAuditDetailTest {

    /** The detail as it was written before task 0016, which Admin › Audit trail parses. */
    private static final Pattern OLD_PREFIX = Pattern.compile(
            "^Sensitive-data policy changed: enabledCount=(\\d+), threshold=(\\d\\.\\d\\d), reviewMode=([A-Z_]+), maskSsnLastFour=(true|false)");

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryPolicies policies = new InMemoryPolicies();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new NoDefinitions(), policies, new NoLifecycle(), new DefaultReporting(), new AuditService(audits::add));

    @Test
    void firstChangeKeepsOldKeysAndAppendsTypesPresetAndPreviousNone() {
        change(EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH, EntityType.CREDIT_CARD_NUMBER), 0.80,
                ReviewMode.UNCERTAIN_ONLY, true);

        String detail = lastPolicyDetail();
        assertEquals("Sensitive-data policy changed: enabledCount=3, threshold=0.80, reviewMode=UNCERTAIN_ONLY, "
                + "maskSsnLastFour=true, enabledTypes=CREDIT_CARD_NUMBER,DATE_OF_BIRTH,SSN, preset=BALANCED, "
                + "previous=none", detail);
        assertOldPrefix(detail, "3", "0.80", "UNCERTAIN_ONLY", "true");
    }

    @Test
    void laterChangeRecordsThePreviousPolicyInBrackets() {
        change(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        change(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE, EntityType.DATE_OF_BIRTH), 0.70,
                ReviewMode.UNCERTAIN_ONLY, false);

        String detail = lastPolicyDetail();
        assertEquals("Sensitive-data policy changed: enabledCount=3, threshold=0.70, reviewMode=UNCERTAIN_ONLY, "
                + "maskSsnLastFour=false, enabledTypes=CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=LIGHT, "
                + "previous=[enabledCount=2, threshold=0.80, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, "
                + "enabledTypes=CREDIT_SCORE,SSN, preset=BALANCED]", detail);
        assertOldPrefix(detail, "3", "0.70", "UNCERTAIN_ONLY", "false");
    }

    @Test
    void presetIsWorkedOutOnTheServerForEveryMode() {
        change(EnumSet.of(EntityType.SSN), 0.85, ReviewMode.REVIEW_ALL, true);
        assertTrue(lastPolicyDetail().contains(", preset=STRICT, previous=none"));
        change(EnumSet.of(EntityType.SSN), 0.81, ReviewMode.UNCERTAIN_ONLY, true);
        assertTrue(lastPolicyDetail().contains(", preset=CUSTOM, previous=[")
                && lastPolicyDetail().endsWith(", preset=STRICT]"));
        change(EnumSet.of(EntityType.SSN), 0.79, ReviewMode.UNCERTAIN_ONLY, true);
        assertTrue(lastPolicyDetail().contains("threshold=0.79") && lastPolicyDetail().contains(", preset=CUSTOM, previous=["));
    }

    @Test
    void detailHoldsOnlyTheKnownKeysAndCategoryNames() {
        change(EnumSet.of(EntityType.SSN, EntityType.CREDIT_SCORE), 0.80, ReviewMode.UNCERTAIN_ONLY, true);
        change(EnumSet.of(EntityType.SSN), 0.70, ReviewMode.REVIEW_ALL, true);

        String detail = lastPolicyDetail().replaceFirst("^Sensitive-data policy changed: ", "");
        // Every key=value pair is one of the known keys; values are numbers, booleans, enum names or "none".
        Matcher pair = Pattern.compile("([A-Za-z]+)=(\\[|[^,\\]]+)").matcher(detail);
        Set<String> allowed = Set.of("enabledCount", "threshold", "reviewMode", "maskSsnLastFour", "enabledTypes",
                "preset", "previous");
        int count = 0;
        while (pair.find()) {
            count++;
            assertTrue(allowed.contains(pair.group(1)), "unexpected key " + pair.group(1));
        }
        assertEquals(13, count, "6 keys now, previous=, 6 keys for the previous policy");
        String withoutKnownWords = detail
                .replaceAll("[A-Za-z]+=", "")
                .replaceAll("CREDIT_CARD_NUMBER|DATE_OF_BIRTH|CREDIT_SCORE|SSN|UNCERTAIN_ONLY|REVIEW_ALL", "")
                .replaceAll("STRICT|BALANCED|LIGHT|CUSTOM|true|false|none", "")
                .replaceAll("[0-9.,\\[\\] ]", "");
        assertEquals("", withoutKnownWords, "nothing but keys, numbers and category names");
        assertFalse(detail.length() > 1000, "never truncated");
    }

    @Test
    void theLongestPossibleDetailIsNotTruncated() {
        Set<EntityType> all = TenantRedactionPolicy.v1ConfigurableTypes();
        change(all, 0.99, ReviewMode.UNCERTAIN_ONLY, false);
        change(all, 0.50, ReviewMode.UNCERTAIN_ONLY, false);
        assertFalse(lastPolicyDetail().contains("[truncated]"));
        assertTrue(lastPolicyDetail().endsWith("preset=CUSTOM]"));
    }

    private void change(Set<EntityType> types, double threshold, ReviewMode mode, boolean maskSsnLastFour) {
        service.updateSensitiveDataPolicy(tenant, ActorId.generate(), CorrelationId.generate(), types, threshold, mode,
                maskSsnLastFour);
    }

    private String lastPolicyDetail() {
        return audits.stream().filter(r -> r.action() == AuditAction.SENSITIVE_DATA_POLICY_CHANGED)
                .reduce((a, b) -> b).orElseThrow().detail();
    }

    private static void assertOldPrefix(String detail, String count, String threshold, String mode, String mask) {
        Matcher m = OLD_PREFIX.matcher(detail);
        assertTrue(m.find(), "old keys, order and format unchanged: " + detail);
        assertEquals(count, m.group(1));
        assertEquals(threshold, m.group(2));
        assertEquals(mode, m.group(3));
        assertEquals(mask, m.group(4));
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
