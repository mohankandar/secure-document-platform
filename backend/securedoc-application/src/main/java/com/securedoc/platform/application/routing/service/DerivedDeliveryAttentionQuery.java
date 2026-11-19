package com.securedoc.platform.application.routing.service;

import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup.CasePrimaryIdentifier;
import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.routing.DerivedDelivery;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Barcode deliveries that need attention, each with its case's main ID when the case has one (UI task U008). */
public final class DerivedDeliveryAttentionQuery {
    private final DerivedDeliveryRepository deliveries;
    private final CasePrimaryIdentifierLookup identifiers;

    public DerivedDeliveryAttentionQuery(DerivedDeliveryRepository deliveries, CasePrimaryIdentifierLookup identifiers) {
        this.deliveries = Objects.requireNonNull(deliveries);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public List<AttentionItem> listAttention(TenantId tenantId) {
        List<DerivedDelivery> items = deliveries.findAttentionByTenantId(tenantId);
        if (items.isEmpty()) return List.of();
        Map<PackageId, CasePrimaryIdentifier> ids = identifiers.findByPackageIds(tenantId,
                items.stream().map(DerivedDelivery::packageId).distinct().toList());
        return items.stream().map(d -> new AttentionItem(d, ids.get(d.packageId()))).toList();
    }

    /** {@code caseIdentifier} is null when the case has no main ID value. */
    public record AttentionItem(DerivedDelivery delivery, CasePrimaryIdentifier caseIdentifier) {}
}
