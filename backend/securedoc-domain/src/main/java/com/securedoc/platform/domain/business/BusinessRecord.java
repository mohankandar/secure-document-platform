package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BusinessRecord {
    private final BusinessRecordId id;
    private final TenantId tenantId;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<BusinessIdentifier> identifiers;

    private BusinessRecord(BusinessRecordId id, TenantId tenantId, Instant createdAt,
                           Instant updatedAt, List<BusinessIdentifier> identifiers) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.identifiers = new ArrayList<>(identifiers == null ? List.of() : identifiers);
    }

    public static BusinessRecord create(TenantId tenantId, List<BusinessIdentifier> identifiers) {
        Instant now = Instant.now();
        BusinessRecord record = new BusinessRecord(BusinessRecordId.generate(), tenantId, now, now, identifiers);
        record.validateIdentifierSet();
        return record;
    }

    public static BusinessRecord reconstitute(BusinessRecordId id, TenantId tenantId, Instant createdAt,
                                               Instant updatedAt, List<BusinessIdentifier> identifiers) {
        return new BusinessRecord(id, tenantId, createdAt, updatedAt, identifiers);
    }

    public void addIdentifier(BusinessIdentifier identifier) {
        Objects.requireNonNull(identifier);
        boolean exists = identifiers.stream().anyMatch(i -> i.definitionId().equals(identifier.definitionId()));
        if (exists) throw new IllegalStateException("Identifier already exists for definition: " + identifier.key());
        identifiers.add(identifier);
        validateIdentifierSet();
        updatedAt = Instant.now();
    }

    private void validateIdentifierSet() {
        long primaryCount = identifiers.stream().filter(BusinessIdentifier::primary).count();
        if (!identifiers.isEmpty() && primaryCount > 1) {
            throw new IllegalStateException("Business record cannot have more than one primary identifier");
        }
    }

    public BusinessRecordId id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<BusinessIdentifier> identifiers() {
        return identifiers.stream().sorted(Comparator.comparingInt(BusinessIdentifier::displayOrder)).toList();
    }
    public Optional<BusinessIdentifier> primaryIdentifier() {
        return identifiers.stream().filter(BusinessIdentifier::primary).findFirst();
    }
}
