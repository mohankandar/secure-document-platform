package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.casefields.TestCaseFields;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0020 B2: the minutes-per-page setting, saved with the report targets, validated and audited. */
class MinutesPerPageSettingTest {

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final InMemoryReporting reporting = new InMemoryReporting();
    private final BusinessAdminConfigurationService service = new BusinessAdminConfigurationService(
            new TestCaseFields().settings(), new NoPolicies(), new NoLifecycle(), reporting, new AuditService(audits::add));

    @Test
    void defaultIsOneMinuteDeliberatelyLow() {
        assertEquals(1.0, ReportingConfiguration.defaults().minutesPerPage());
        assertEquals(1.0, service.getConfiguration(tenant).reportingConfiguration().minutesPerPage());
    }

    @Test
    void halfToThirtyInStepsOfAHalfOthersAreRefusedAndNothingIsSavedOrRecorded() {
        for (double good : new double[]{0.5, 1.0, 2.5, 29.5, 30}) {
            assertEquals(good, save(good).minutesPerPage(), "allowed: " + good);
        }
        int records = audits.size();
        for (double bad : new double[]{0.4, 30.5, 1.25, 0, -1, Double.NaN}) {
            ValidationException e = assertThrows(ValidationException.class, () -> save(bad), "refused: " + bad);
            assertTrue(e.getMessage().contains("minutesPerPage"));
        }
        assertEquals(30, reporting.stored(tenant).minutesPerPage(), "the last good value stays");
        assertEquals(records, audits.size(), "a refused save writes no record");
    }

    @Test
    void theChangeIsAuditedWithTheNewValueAndPreviousInTheSameKeyStyle() {
        save(2.5);
        AuditRecord r = only();
        assertEquals("Reporting configuration changed: dimensions=0; deliveryTargetMinutes=240; deliveryTargetPercent=95.0; "
                + "straightThroughTargetPercent=90.0; reviewWaitTargetMinutes=60; reviewWaitTargetPercent=80; minutesPerPage=2.5; "
                + "previous=[dimensions=0; deliveryTargetMinutes=240; deliveryTargetPercent=95.0; straightThroughTargetPercent=90.0; "
                + "reviewWaitTargetMinutes=60; reviewWaitTargetPercent=80; minutesPerPage=1.0]", r.detail());
        // Numbers only: every value after "key=" is a number (REPORTING_CONFIGURATION_CHANGED is on the appendix list).
        String values = r.detail().replace("Reporting configuration changed: ", "").replace("previous=[", "").replace("]", "");
        for (String pair : values.split("; ")) {
            assertTrue(pair.matches("[A-Za-z]+=[0-9]+(\\.[0-9]+)?"), pair);
        }
    }

    @Test
    void noRecordWhenNothingChangedAndAnOlderCallerKeepsTheStoredValue() {
        save(2.5);
        save(2.5);
        assertEquals(1, audits.size(), "a save with no change writes no record");
        assertEquals(1, reporting.saves, "and saves nothing");
        // The older admin page sends no minutes per page: the stored value is kept, and nothing changed.
        service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), List.of(),
                240, 95.0, 90.0, 60, 80, null);
        assertEquals(2.5, reporting.stored(tenant).minutesPerPage());
        assertEquals(1, audits.size());
        // Another target changing is recorded, with the minutes per page unchanged on both sides.
        service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), List.of(),
                120, 95.0, 90.0, 60, 80, null);
        assertEquals(2, audits.size());
        assertTrue(audits.get(1).detail().contains("deliveryTargetMinutes=120;"));
        assertTrue(audits.get(1).detail().contains("minutesPerPage=2.5; previous=[dimensions=0; deliveryTargetMinutes=240;"));
        assertTrue(audits.get(1).detail().endsWith("minutesPerPage=2.5]"));
    }

    @Test
    void eachEntityHasItsOwnSetting() {
        TenantId other = TenantId.generate();
        save(4.0);
        assertEquals(1.0, service.getConfiguration(other).reportingConfiguration().minutesPerPage());
        assertEquals(4.0, service.getConfiguration(tenant).reportingConfiguration().minutesPerPage());
    }

    private ReportingConfiguration save(double minutesPerPage) {
        return service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), List.of(),
                240, 95.0, 90.0, 60, 80, minutesPerPage);
    }

    private AuditRecord only() {
        List<AuditRecord> found = audits.stream().filter(a -> a.action() == AuditAction.REPORTING_CONFIGURATION_CHANGED).toList();
        assertEquals(1, found.size());
        return found.getFirst();
    }

    private static final class InMemoryReporting implements ReportingConfigurationRepository {
        private final Map<TenantId, ReportingConfiguration> byTenant = new HashMap<>();
        private int saves;
        ReportingConfiguration stored(TenantId t) { return findByTenantId(t); }
        public ReportingConfiguration findByTenantId(TenantId t) { return byTenant.getOrDefault(t, ReportingConfiguration.defaults()); }
        public void save(TenantId t, ReportingConfiguration c) { byTenant.put(t, c); saves++; }
    }

    private static final class NoPolicies implements TenantRedactionPolicyRepository {
        public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) { return Optional.empty(); }
        public void save(TenantRedactionPolicy policy) { }
    }

    private static final class NoLifecycle implements TenantLifecyclePolicyRepository {
        public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) { return Optional.empty(); }
        public void save(TenantLifecyclePolicy policy) { }
    }

}
