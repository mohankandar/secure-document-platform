package com.securedoc.platform.application.routing;

import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup.CasePrimaryIdentifier;
import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.application.routing.service.DerivedDeliveryAttentionQuery;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryAttempt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedDeliveryAttentionQueryTest {
    private final TenantId tenant = TenantId.generate();
    private final Instant now = Instant.parse("2026-11-18T10:00:00Z");

    @Test
    void eachItemCarriesItsCaseMainIdAndACaseWithoutOneGetsNull() {
        PackageId withId = PackageId.generate();
        PackageId withoutId = PackageId.generate();
        DerivedDelivery a = attention(withId);
        DerivedDelivery b = attention(withId);
        DerivedDelivery c = attention(withoutId);
        List<Collection<PackageId>> asked = new ArrayList<>();
        CasePrimaryIdentifierLookup lookup = (t, ids) -> {
            assertEquals(tenant, t);
            asked.add(ids);
            return Map.of(withId, new CasePrimaryIdentifier("Loan number", "LN204812"));
        };
        var items = new DerivedDeliveryAttentionQuery(repository(List.of(a, b, c)), lookup).listAttention(tenant);

        assertEquals(3, items.size());
        assertSame(a, items.get(0).delivery());
        assertEquals("LN204812", items.get(0).caseIdentifier().value());
        assertEquals("Loan number", items.get(1).caseIdentifier().label());
        assertNull(items.get(2).caseIdentifier());
        // One lookup for all items, each package once.
        assertEquals(1, asked.size());
        assertEquals(2, asked.get(0).size());
    }

    @Test
    void noAttentionMeansNoLookup() {
        CasePrimaryIdentifierLookup lookup = (t, ids) -> { throw new AssertionError("must not be called"); };
        assertTrue(new DerivedDeliveryAttentionQuery(repository(List.of()), lookup).listAttention(tenant).isEmpty());
    }

    private DerivedDelivery attention(PackageId packageId) {
        return DerivedDelivery.routingAttention(tenant, packageId, DocumentId.generate(), 1, List.of(3, 4), "DELIVERY_IO", now);
    }

    private DerivedDeliveryRepository repository(List<DerivedDelivery> attention) {
        return new DerivedDeliveryRepository() {
            public List<DerivedDelivery> findByTenantIdAndPackageId(TenantId t, PackageId p) { return List.of(); }
            public Optional<DerivedDelivery> findByTenantIdAndId(TenantId t, UUID id) { return Optional.empty(); }
            public List<DerivedDelivery> findAttentionByTenantId(TenantId t) { return attention; }
            public Optional<DerivedDelivery> findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(
                    TenantId t, PackageId p, int cycle, DocumentId d, UUID rule) { return Optional.empty(); }
            public void save(DerivedDelivery d) { }
            public void saveAttempt(DerivedDeliveryAttempt a) { }
        };
    }
}
