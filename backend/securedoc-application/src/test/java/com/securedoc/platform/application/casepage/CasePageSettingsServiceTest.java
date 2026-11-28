package com.securedoc.platform.application.casepage;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CasePageSettingsStore;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0108: the entity's Cases-page settings: defaults, ranges refused whole, one audit record per real change. */
class CasePageSettingsServiceTest {
    private final TenantId tenant = TenantId.generate();
    private final ActorId admin = ActorId.generate();
    private final Map<TenantId, CasePageSettings> rows = new HashMap<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final CasePageSettingsStore store = new CasePageSettingsStore() {
        public Optional<CasePageSettings> find(TenantId t) { return Optional.ofNullable(rows.get(t)); }
        public void save(TenantId t, ActorId a, CasePageSettings s) { rows.put(t, s); }
        public OptionalInt shortestExpiryDays() {
            return rows.values().stream().mapToInt(CasePageSettings::emptyCaseExpiryDays).min();
        }
    };
    private final CasePageSettingsService service = new CasePageSettingsService(store, new AuditService(audits::add), 7);

    @Test
    void anEntityWithoutARowHasTheDefaults() {
        assertEquals(new CasePageSettings(240, 480, 7), service.read(tenant));
        assertEquals(Duration.ofDays(7), service.emptyCaseExpiry(tenant));
        assertEquals(Duration.ofDays(7), service.shortestEmptyCaseExpiry());
    }

    @Test
    void savesAndAuditsOnlyARealChange() {
        service.save(tenant, admin, CorrelationId.generate(), 120, 300, 3);
        assertEquals(new CasePageSettings(120, 300, 3), service.read(tenant));
        assertEquals(1, audits.size());
        service.save(tenant, admin, CorrelationId.generate(), 120, 300, 3);
        assertEquals(1, audits.size(), "the same values again: nothing written");
        assertEquals(Duration.ofDays(3), service.shortestEmptyCaseExpiry());
    }

    @Test
    void refusesOutOfRangeValuesWhole() {
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), 300, 300, 7),
                "red must be later than amber");
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), 0, 60, 7));
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), 60, 20000, 7));
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), 60, 120, 0));
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), 60, 120, 366));
        assertThrows(ValidationException.class, () -> service.save(tenant, admin, CorrelationId.generate(), null, 120, 7));
        assertTrue(rows.isEmpty(), "nothing stored");
        assertTrue(audits.isEmpty());
    }
}
